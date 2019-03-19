/*******************************************************************************
 * Copyright (c) 2016, 2019 Eurotech and/or its affiliates and others
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Eurotech - initial API and implementation
 *******************************************************************************/
package org.eclipse.kapua.test.user;

import org.eclipse.kapua.locator.KapuaProvider;
import org.eclipse.kapua.locator.guice.TestService;
import org.eclipse.kapua.model.id.KapuaId;
import org.eclipse.kapua.service.user.User;
import org.eclipse.kapua.service.user.UserCreator;
import org.eclipse.kapua.service.user.UserFactory;
import org.eclipse.kapua.service.user.UserListResult;
import org.eclipse.kapua.service.user.UserQuery;

@TestService
@KapuaProvider
public class UserFactoryMock implements UserFactory {

    @Override
    public UserCreator newCreator(KapuaId scopeId, String name) {
        UserCreatorMock userCreatorMock = new UserCreatorMock();
        userCreatorMock.setScopeId(scopeId);
        userCreatorMock.setName(name);
        return userCreatorMock;
    }

    @Override
    public UserQuery newQuery(KapuaId scopedId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public User newEntity(KapuaId scopeId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public UserCreator newCreator(KapuaId scopeId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public UserListResult newListResult() {
        throw new UnsupportedOperationException();
    }

    @Override
    public User clone(User entity) {
        throw new UnsupportedOperationException();
    }
}
