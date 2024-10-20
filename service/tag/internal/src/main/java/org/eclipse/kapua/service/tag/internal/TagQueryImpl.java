/*******************************************************************************
 * Copyright (c) 2017, 2022 Eurotech and/or its affiliates and others
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
package org.eclipse.kapua.service.tag.internal;

import org.eclipse.kapua.commons.model.query.AbstractKapuaNamedQuery;
import org.eclipse.kapua.model.id.KapuaId;
import org.eclipse.kapua.service.tag.TagMatchPredicate;
import org.eclipse.kapua.service.tag.TagQuery;

/**
 * {@link TagQuery} implementation.
 *
 * @since 1.0.0
 */
public class TagQueryImpl extends AbstractKapuaNamedQuery implements TagQuery {

    /**
     * Constructor.
     *
     * @param scopeId The {@link #getScopeId()}.
     * @since 1.0.0
     */
    public TagQueryImpl(KapuaId scopeId) {
        super(scopeId);
    }

    @Override
    public <T> TagMatchPredicate<T> matchPredicate(T matchTerm) {
        return new TagMatchPredicateImpl<>(matchTerm);
    }

}
