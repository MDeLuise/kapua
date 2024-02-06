/*******************************************************************************
 * Copyright (c) 2020, 2022 Eurotech and/or its affiliates and others
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     Eurotech - initial API and implementation
 *******************************************************************************/
package org.eclipse.kapua.service.authentication.credential.mfa.shiro;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import org.apache.commons.lang.time.DateUtils;
import org.apache.http.client.utils.URIBuilder;
import org.eclipse.kapua.KapuaEntityNotFoundException;
import org.eclipse.kapua.KapuaException;
import org.eclipse.kapua.commons.model.domains.Domains;
import org.eclipse.kapua.commons.security.KapuaSecurityUtils;
import org.eclipse.kapua.commons.security.KapuaSession;
import org.eclipse.kapua.commons.util.ArgumentValidator;
import org.eclipse.kapua.model.KapuaEntityAttributes;
import org.eclipse.kapua.model.domain.Actions;
import org.eclipse.kapua.model.id.KapuaId;
import org.eclipse.kapua.model.query.KapuaQuery;
import org.eclipse.kapua.service.account.Account;
import org.eclipse.kapua.service.account.AccountService;
import org.eclipse.kapua.service.authentication.credential.mfa.KapuaExistingMfaOptionException;
import org.eclipse.kapua.service.authentication.credential.mfa.KapuaExistingScratchCodesException;
import org.eclipse.kapua.service.authentication.credential.mfa.MfaOption;
import org.eclipse.kapua.service.authentication.credential.mfa.MfaOptionAttributes;
import org.eclipse.kapua.service.authentication.credential.mfa.MfaOptionCreator;
import org.eclipse.kapua.service.authentication.credential.mfa.MfaOptionListResult;
import org.eclipse.kapua.service.authentication.credential.mfa.MfaOptionRepository;
import org.eclipse.kapua.service.authentication.credential.mfa.MfaOptionService;
import org.eclipse.kapua.service.authentication.credential.mfa.ScratchCode;
import org.eclipse.kapua.service.authentication.credential.mfa.ScratchCodeCreator;
import org.eclipse.kapua.service.authentication.credential.mfa.ScratchCodeFactory;
import org.eclipse.kapua.service.authentication.credential.mfa.ScratchCodeListResult;
import org.eclipse.kapua.service.authentication.credential.mfa.ScratchCodeRepository;
import org.eclipse.kapua.service.authentication.mfa.MfaAuthenticator;
import org.eclipse.kapua.service.authentication.shiro.utils.AuthenticationUtils;
import org.eclipse.kapua.service.authentication.shiro.utils.CryptAlgorithm;
import org.eclipse.kapua.service.authorization.AuthorizationService;
import org.eclipse.kapua.service.authorization.exception.InternalUserOnlyException;
import org.eclipse.kapua.service.authorization.exception.SelfManagedOnlyException;
import org.eclipse.kapua.service.authorization.permission.PermissionFactory;
import org.eclipse.kapua.service.user.User;
import org.eclipse.kapua.service.user.UserService;
import org.eclipse.kapua.service.user.UserType;
import org.eclipse.kapua.storage.TxManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link MfaOptionService} implementation.
 *
 * @since 1.3.0
 */
public class MfaOptionServiceImpl implements MfaOptionService {

    private static final Logger LOGGER = LoggerFactory.getLogger(MfaOptionServiceImpl.class);

    private final int trustKeyDuration;
    private static final int QR_CODE_SIZE = 134;  // TODO: make this configurable?
    private static final String IMAGE_FORMAT = "png";
    private final MfaAuthenticator mfaAuthenticator;
    private final TxManager txManager;
    private final MfaOptionRepository mfaOptionRepository;
    private final AccountService accountService;
    private final ScratchCodeRepository scratchCodeRepository;
    private final ScratchCodeFactory scratchCodeFactory;
    private final AuthorizationService authorizationService;
    private final PermissionFactory permissionFactory;
    private final UserService userService;
    private final AuthenticationUtils authenticationUtils;

    public MfaOptionServiceImpl(
            int trustKeyDuration,
            MfaAuthenticator mfaAuthenticator, TxManager txManager,
            MfaOptionRepository mfaOptionRepository,
            AccountService accountService,
            ScratchCodeRepository scratchCodeRepository,
            ScratchCodeFactory scratchCodeFactory,
            AuthorizationService authorizationService,
            PermissionFactory permissionFactory,
            UserService userService,
            AuthenticationUtils authenticationUtils) {
        this.trustKeyDuration = trustKeyDuration;
        this.mfaAuthenticator = mfaAuthenticator;
        this.txManager = txManager;
        this.mfaOptionRepository = mfaOptionRepository;
        this.accountService = accountService;
        this.scratchCodeRepository = scratchCodeRepository;
        this.scratchCodeFactory = scratchCodeFactory;
        this.authorizationService = authorizationService;
        this.permissionFactory = permissionFactory;
        this.userService = userService;
        this.authenticationUtils = authenticationUtils;
    }

    @Override
    public MfaOption create(final MfaOptionCreator mfaOptionCreator) throws KapuaException {
        // Argument Validation
        ArgumentValidator.notNull(mfaOptionCreator, "mfaOptionCreator");
        ArgumentValidator.notNull(mfaOptionCreator.getScopeId(), "mfaOptionCreator.scopeId");
        ArgumentValidator.notNull(mfaOptionCreator.getUserId(), "mfaOptionCreator.userId");
        // Check access
        authorizationService.checkPermission(permissionFactory.newPermission(Domains.CREDENTIAL, Actions.write, mfaOptionCreator.getScopeId()));
        // Check that the operation is carried by the user itself
        final KapuaSession session = KapuaSecurityUtils.getSession();
        final KapuaId expectedUser = session.getUserId();
        if (!expectedUser.equals(mfaOptionCreator.getUserId())) {
            throw new SelfManagedOnlyException();
        }
        final String fullKey = mfaAuthenticator.generateKey();

        final MfaOption option = txManager.execute(tx -> {
            // Check that the user is an internal user (external users cannot have the MFA enabled)
            final MfaOptionCreator finalMfaOptionCreator = mfaOptionCreator;
            User user = Optional.ofNullable(
                    KapuaSecurityUtils.doPrivileged(() ->
                            userService.find(finalMfaOptionCreator.getScopeId(), finalMfaOptionCreator.getUserId())
                    )
            ).orElseThrow(() -> new KapuaEntityNotFoundException(User.TYPE, finalMfaOptionCreator.getUserId()));
            if (!user.getUserType().equals(UserType.INTERNAL) || user.getExternalId() != null) {
                throw new InternalUserOnlyException();
            }
            // Check existing MfaOption
            Optional<MfaOption> existingMfaOption = mfaOptionRepository.findByUserId(tx, mfaOptionCreator.getScopeId(), mfaOptionCreator.getUserId());
            if (existingMfaOption.isPresent()) {
                throw new KapuaExistingMfaOptionException();
            }

            // Do create
            MfaOption toCreate = new MfaOptionImpl(mfaOptionCreator.getScopeId());
            toCreate.setUserId(mfaOptionCreator.getUserId());
            toCreate.setMfaSecretKey(fullKey);
            final MfaOption mfaOption = mfaOptionRepository.create(tx, toCreate);

            // generating base64 QR code image
            Account account = Optional.ofNullable(
                    KapuaSecurityUtils.doPrivileged(() ->
                            accountService.find(KapuaId.ANY, finalMfaOptionCreator.getScopeId())
                    )
            ).orElseThrow(() -> new KapuaEntityNotFoundException(Account.TYPE, finalMfaOptionCreator.getScopeId()));
            mfaOption.setQRCodeImage(generateQRCode(account.getOrganization().getName(), account.getName(), user.getName(), fullKey));

            return mfaOption;
        });

        // generating scratch codes
        final ScratchCodeCreator scratchCodeCreator = scratchCodeFactory.newCreator(mfaOptionCreator.getScopeId(), option.getId(), null);
        final ScratchCodeListResult scratchCodeListResult = createAllScratchCodes(scratchCodeCreator);
        final ScratchCodeListResult scratchCodes = scratchCodeListResult;
        option.setScratchCodes(scratchCodes.getItems());

        // Do post persist magic on key value (note that this is the only place in which the key is returned in plain-text)
        option.setMfaSecretKey(fullKey);
        return option;
    }

    /**
     * Generates all the scratch codes.
     * The number of generated scratch codes is decided through the {@link org.eclipse.kapua.service.authentication.mfa.MfaAuthenticator} service.
     * The scratch code provided within the scratchCodeCreator parameter is ignored.
     *
     * @param scratchCodeCreator
     * @return
     * @throws KapuaException
     */
    public ScratchCodeListResult createAllScratchCodes(ScratchCodeCreator scratchCodeCreator) throws KapuaException {
        // Argument Validation
        ArgumentValidator.notNull(scratchCodeCreator, "scratchCodeCreator");
        ArgumentValidator.notNull(scratchCodeCreator.getScopeId(), "scratchCodeCreator.scopeId");
        ArgumentValidator.notNull(scratchCodeCreator.getMfaOptionId(), "scratchCodeCreator.mfaOptionId");

        List<String> codes = mfaAuthenticator.generateCodes();
        ScratchCodeListResult scratchCodeListResult = new ScratchCodeListResultImpl();
        // Check existing ScratchCodes
        ScratchCodeListResult existingScratchCodeListResult = txManager.execute(tx ->
                scratchCodeRepository.findByMfaOptionId(tx, scratchCodeCreator.getScopeId(), scratchCodeCreator.getMfaOptionId()));
        if (!existingScratchCodeListResult.isEmpty()) {
            throw new KapuaExistingScratchCodesException();
        }

        for (String code : codes) {
            scratchCodeCreator.setCode(code);
            // Crypto code (it's ok to do than if BCrypt is used when checking a provided scratch code against the stored one)
            String encryptedCode = authenticationUtils.cryptCredential(CryptAlgorithm.BCRYPT, scratchCodeCreator.getCode());
            // Create code
            ScratchCodeImpl codeImpl = new ScratchCodeImpl(scratchCodeCreator.getScopeId(), scratchCodeCreator.getMfaOptionId(), encryptedCode);

            ScratchCode scratchCode = txManager.execute(tx -> scratchCodeRepository.create(tx, codeImpl));

            // setting back the non-encrypted code, so it can be read from the user only one time after creation
            scratchCode.setCode(code);

            scratchCodeListResult.addItem(scratchCode);
        }

        return scratchCodeListResult;
    }

    @Override
    public MfaOption update(MfaOption mfaOption) throws KapuaException {
        // Argument Validation
        ArgumentValidator.notNull(mfaOption, "mfaOption");
        ArgumentValidator.notNull(mfaOption.getId(), "mfaOption.id");
        ArgumentValidator.notNull(mfaOption.getScopeId(), "mfaOption.scopeId");
        ArgumentValidator.notNull(mfaOption.getUserId(), "mfaOption.userId");
        ArgumentValidator.notEmptyOrNull(mfaOption.getMfaSecretKey(), "mfaOption.mfaSecretKey");
        // Check access
        authorizationService.checkPermission(permissionFactory.newPermission(Domains.CREDENTIAL, Actions.write, mfaOption.getScopeId()));

        return txManager.execute(tx -> mfaOptionRepository.update(tx, mfaOption));
    }

    @Override
    public MfaOption find(KapuaId scopeId, KapuaId mfaOptionId) throws KapuaException {
        // Validation of the fields
        ArgumentValidator.notNull(scopeId, KapuaEntityAttributes.SCOPE_ID);
        ArgumentValidator.notNull(mfaOptionId, "mfaOptionId");
        // Check Access
        authorizationService.checkPermission(permissionFactory.newPermission(Domains.CREDENTIAL, Actions.read, scopeId));

        return txManager.execute(tx -> mfaOptionRepository.find(tx, scopeId, mfaOptionId))
                .orElse(null);
    }

    @Override
    public MfaOptionListResult query(KapuaQuery query) throws KapuaException {
        // Argument Validation
        ArgumentValidator.notNull(query, "query");
        // Check Access
        authorizationService.checkPermission(permissionFactory.newPermission(Domains.CREDENTIAL, Actions.read, query.getScopeId()));

        return txManager.execute(tx -> mfaOptionRepository.query(tx, query));
    }

    @Override
    public long count(KapuaQuery query) throws KapuaException {
        // Argument Validation
        ArgumentValidator.notNull(query, "query");
        // Check Access
        authorizationService.checkPermission(permissionFactory.newPermission(Domains.CREDENTIAL, Actions.read, query.getScopeId()));

        return txManager.execute(tx -> mfaOptionRepository.count(tx, query));
    }

    @Override
    public void delete(KapuaId scopeId, KapuaId mfaOptionId) throws KapuaException {
        // Argument Validation
        ArgumentValidator.notNull(mfaOptionId, "mfaOptionId");
        ArgumentValidator.notNull(scopeId, "scopeId");
        // Check Access
        authorizationService.checkPermission(permissionFactory.newPermission(Domains.CREDENTIAL, Actions.delete, scopeId));

        txManager.execute(tx -> mfaOptionRepository.delete(tx, scopeId, mfaOptionId));
    }

    @Override
    public MfaOption findByUserId(KapuaId scopeId, KapuaId userId) throws KapuaException {
        // Argument Validation
        ArgumentValidator.notNull(scopeId, KapuaEntityAttributes.SCOPE_ID);
        ArgumentValidator.notNull(userId, MfaOptionAttributes.USER_ID);
        // Check Access
        authorizationService.checkPermission(permissionFactory.newPermission(Domains.CREDENTIAL, Actions.read, scopeId));

        return txManager.execute(tx -> mfaOptionRepository.findByUserId(tx, scopeId, userId))
                .orElse(null);
    }

    @Override
    public String enableTrust(MfaOption mfaOption) throws KapuaException {

        // Argument Validation (fields validation is performed inside the 'update' method)
        ArgumentValidator.notNull(mfaOption, "mfaOption");

        return enableTrust(mfaOption.getScopeId(), mfaOption.getId());
    }

    @Override
    public String enableTrust(KapuaId scopeId, KapuaId mfaOptionId) throws KapuaException {
        return doEnableTrust(scopeId, mfaOptionId);
    }

    private String doEnableTrust(KapuaId scopeId, KapuaId mfaOptionId) throws KapuaException {
        // Argument Validation
        ArgumentValidator.notNull(scopeId, "scopeId");
        ArgumentValidator.notNull(mfaOptionId, "mfaOptionId");
        return txManager.execute(tx -> {
            // Checking existence
            MfaOption mfaOption = mfaOptionRepository.find(tx, scopeId, mfaOptionId)
                    .orElseThrow(() -> new KapuaEntityNotFoundException(MfaOption.TYPE, mfaOptionId));

            // Trust key generation always performed
            // This allows the use only of a single trusted machine,
            // until a solution with different trust keys is implemented!
            final String trustKey = generateTrustKey();
            mfaOption.setTrustKey(authenticationUtils.cryptCredential(CryptAlgorithm.BCRYPT, trustKey));

            Date expirationDate = new Date(System.currentTimeMillis());
            expirationDate = DateUtils.addDays(expirationDate, trustKeyDuration);
            mfaOption.setTrustExpirationDate(expirationDate);

            // Update
            mfaOptionRepository.update(tx, mfaOption);

            return trustKey;
        });
    }

    @Override
    public void disableTrust(KapuaId scopeId, KapuaId mfaOptionId) throws KapuaException {
        txManager.execute(tx -> {
            // Argument Validation
            ArgumentValidator.notNull(mfaOptionId, "mfaOptionId");
            ArgumentValidator.notNull(scopeId, "scopeId");

            // extracting the MfaOption
            MfaOption mfaOption = mfaOptionRepository.find(tx, scopeId, mfaOptionId)
                    .orElseThrow(() -> new KapuaEntityNotFoundException(MfaOption.TYPE, mfaOptionId));

            // Reset the trust machine fields
            mfaOption.setTrustKey(null);
            mfaOption.setTrustExpirationDate(null);

            return mfaOptionRepository.update(tx, mfaOption);
        });
    }

    /**
     * Generate the trust key string.
     *
     * @return String
     * @since 1.3.0
     */
    private String generateTrustKey() {
        return UUID.randomUUID().toString();
    }

    /**
     * Produce a QR code in base64 format for the authenticator app.
     * This QR code generator follows the spec detailed here for the URI format: https://github.com/google/google-authenticator/wiki/Key-Uri-Format
     *
     * @param organizationName the organization name to be used as issuer in the QR code
     * @param accountName      the account name of the account to which the user belongs
     * @param username         the username
     * @param key              the Mfa secret key in plain text
     * @return the QR code image in base64 format
     * @since 1.3.0
     */
    private String generateQRCode(String organizationName, String accountName, String username, String key) {
        // url to qr_barcode encoding
        URI uri = null;
        try {
            uri = new URIBuilder()
                    .setScheme("otpauth")
                    .setHost("totp")
                    .setPath(organizationName + ":" + username + "@" + accountName)
                    .setParameter("secret", key)
                    .setParameter("issuer", organizationName)
                    .build();
            BitMatrix bitMatrix = null;
            bitMatrix = new QRCodeWriter().encode(uri.toString(), BarcodeFormat.QR_CODE, QR_CODE_SIZE, QR_CODE_SIZE);
            BufferedImage image = buildImage(bitMatrix);
            return imgToBase64(image);
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        } catch (WriterException e) {
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Converts a {@link BufferedImage} to base64 string format
     *
     * @param img the {@link BufferedImage} to convert
     * @return the base64 string representation of the input image
     * @since 1.3.0
     */
    private static String imgToBase64(BufferedImage img) throws IOException {
        final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        ImageIO.write(img, IMAGE_FORMAT, outputStream);
        return Base64.getEncoder().encodeToString(outputStream.toByteArray());
    }

    /**
     * Converts a {@link BitMatrix} to a {@link BufferedImage}
     *
     * @param bitMatrix the {@link BitMatrix} to be converted into ad image
     * @return the {@link BufferedImage} obtained from the conversion
     * @since 1.3.0
     */
    private static BufferedImage buildImage(BitMatrix bitMatrix) {
        BufferedImage qrCodeImage = MatrixToImageWriter.toBufferedImage(bitMatrix);
        BufferedImage resultImage = new BufferedImage(QR_CODE_SIZE, QR_CODE_SIZE, BufferedImage.TYPE_INT_RGB);

        Graphics g = resultImage.getGraphics();
        g.drawImage(qrCodeImage, 0, 0, new Color(232, 232, 232, 255), null);

        return resultImage;
    }
}
