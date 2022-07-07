/*******************************************************************************
 * Copyright (c) 2022 Eurotech and/or its affiliates and others
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
package org.eclipse.kapua.app.console.module.authentication.shared.model;

import org.junit.Assert;
import org.junit.Test;

public class GwtCredentialTest {

    @Test
    public void shouldSetCorrectPassword() {
        GwtCredential gwtCredential = new GwtCredential();
        String password = "foo%!`\"";
        gwtCredential.setCredentialKey(password);
        Assert.assertEquals(password, gwtCredential.getCredentialKey());
    }

}
