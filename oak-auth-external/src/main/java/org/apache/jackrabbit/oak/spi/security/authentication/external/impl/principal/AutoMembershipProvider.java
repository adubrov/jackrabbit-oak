/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.jackrabbit.oak.spi.security.authentication.external.impl.principal;

import com.google.common.collect.Iterators;
import org.apache.jackrabbit.api.security.user.Authorizable;
import org.apache.jackrabbit.api.security.user.Group;
import org.apache.jackrabbit.api.security.user.UserManager;
import org.apache.jackrabbit.commons.iterator.AbstractLazyIterator;
import org.apache.jackrabbit.commons.iterator.RangeIteratorAdapter;
import org.apache.jackrabbit.oak.api.PropertyValue;
import org.apache.jackrabbit.oak.api.QueryEngine;
import org.apache.jackrabbit.oak.api.Result;
import org.apache.jackrabbit.oak.api.ResultRow;
import org.apache.jackrabbit.oak.api.Root;
import org.apache.jackrabbit.oak.namepath.NamePathMapper;
import org.apache.jackrabbit.oak.plugins.memory.PropertyValues;
import org.apache.jackrabbit.oak.spi.security.authentication.external.ExternalIdentityRef;
import org.apache.jackrabbit.oak.spi.security.authentication.external.basic.DefaultSyncContext;
import org.apache.jackrabbit.oak.spi.security.user.DynamicMembershipProvider;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import javax.jcr.query.Query;
import java.security.Principal;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.apache.jackrabbit.oak.spi.security.authentication.external.impl.ExternalIdentityConstants.REP_EXTERNAL_ID;
import static org.apache.jackrabbit.oak.spi.security.user.UserConstants.NT_REP_USER;
import static org.apache.jackrabbit.oak.spi.security.user.UserConstants.REP_AUTHORIZABLE_ID;

class AutoMembershipProvider implements DynamicMembershipProvider {

    private static final Logger log = LoggerFactory.getLogger(AutoMembershipProvider.class);

    private static final String BINDING_AUTHORIZABLE_IDS = "authorizableIds";

    private final Root root;
    private final UserManager userManager;
    private final NamePathMapper namePathMapper;
    private final AutoMembershipPrincipals autoMembershipPrincipals;
    
    AutoMembershipProvider(@NotNull Root root, 
                           @NotNull UserManager userManager, @NotNull NamePathMapper namePathMapper, 
                           @NotNull Map<String, String[]> autoMembershipMapping) {
        this.root = root;
        this.userManager = userManager;
        this.namePathMapper = namePathMapper;
        this.autoMembershipPrincipals = new AutoMembershipPrincipals(userManager, autoMembershipMapping);
    }
    
    @Override
    public boolean coversAllMembers(@NotNull Group group) {
        return false;
    }

    @Override
    public @NotNull Iterator<Authorizable> getMembers(@NotNull Group group, boolean includeInherited) throws RepositoryException {
        Principal p = getPrincipalOrNull(group);
        if (p == null) {
            return RangeIteratorAdapter.EMPTY;
        }

        // retrieve all idp-names for which the given group-principal is configured in the auto-membership option
        // NOTE: while the configuration takes the group-id the cache in 'autoMembershipPrincipals' is built based on the principal
        Set<String> idpNames = autoMembershipPrincipals.getConfiguredIdpNames(p);
        if (idpNames.isEmpty()) {
            return RangeIteratorAdapter.EMPTY;
        }
        
        // since this provider is only enabled for dynamic-automembership only users are expected to be returned by the
        // query and thus the 'includeInherited' flag can be ignored.
        List<Iterator<Authorizable>> results = new ArrayList<>(idpNames.size());
        // TODO: execute a single (more complex) query ?
        for (String idpName : idpNames) {
            Map<String, ? extends PropertyValue> bindings = buildBinding(idpName);
            String statement = "SELECT '" + REP_AUTHORIZABLE_ID + "' FROM ["+NT_REP_USER+"] WHERE PROPERTY(["
                    + REP_EXTERNAL_ID + "], '" + PropertyType.TYPENAME_STRING + "')"
                    + " LIKE $" + BINDING_AUTHORIZABLE_IDS + QueryEngine.INTERNAL_SQL2_QUERY;
            try {
                Result qResult = root.getQueryEngine().executeQuery(statement, Query.JCR_SQL2, bindings, namePathMapper.getSessionLocalMappings());
                Iterator<Authorizable> it = StreamSupport.stream(qResult.getRows().spliterator(), false).map((Function<ResultRow, Authorizable>) resultRow -> {
                    try {
                        return userManager.getAuthorizableByPath(namePathMapper.getJcrPath(resultRow.getPath()));
                    } catch (RepositoryException e) {
                        return null;
                    }
                }).filter(Objects::nonNull).iterator();
                results.add(it);
            } catch (ParseException e) {
                throw new RepositoryException("Failed to retrieve members of auto-membership group "+ group);
            }
        }
        return Iterators.concat(results.toArray(new Iterator[0]));
    }

    @Override
    public boolean isMember(@NotNull Group group, @NotNull Authorizable authorizable, boolean includeInherited) throws RepositoryException {
        String idpName = getIdpName(authorizable);
        if (idpName == null || authorizable.isGroup()) {
            // not an external user (NOTE: with dynamic membership enabled external groups will not be synced into the repository)
            return false;
        }

        // since this provider is only enabled for dynamic-automembership (external groups not synced), the 
        // 'includeInherited' flag can be ignored.        
        Collection<Principal> groupPrincipals = autoMembershipPrincipals.getPrincipals(idpName);
        return groupPrincipals.contains(group.getPrincipal());
    }

    @Override
    public @NotNull Iterator<Group> getMembership(@NotNull Authorizable authorizable, boolean includeInherited) throws RepositoryException {
        String idpName = getIdpName(authorizable);
        if (idpName == null || authorizable.isGroup()) {
            // not an external user (NOTE: with dynamic membership enabled external groups will not be synced into the repository)
            return RangeIteratorAdapter.EMPTY;
        }
        Collection<Principal> groupPrincipals = autoMembershipPrincipals.getPrincipals(idpName);
        Set<Group> groups = groupPrincipals.stream().map(principal -> {
            try {
                Authorizable a = userManager.getAuthorizable(principal);
                if (a != null && a.isGroup()) {
                    return (Group) a;
                } else {
                    return null;
                }
            } catch (RepositoryException e) {
                return null;
            }
        }).filter(Objects::nonNull).collect(Collectors.toSet());
        Iterator<Group> groupIt = new RangeIteratorAdapter(groups);
        
        if (!includeInherited) {
            return groupIt;
        } else {
            Set<Group> processed = new HashSet<>();
            return Iterators.filter(new InheritedMembershipIterator(groupIt), processed::add);
        }
    }
    
    @Nullable
    private static Principal getPrincipalOrNull(@NotNull Group group) {
        try {
            return group.getPrincipal();
        } catch (RepositoryException e) {
            return null;
        }
    }
    
    @Nullable
    private static String getIdpName(@NotNull Authorizable authorizable) throws RepositoryException {
        ExternalIdentityRef ref = DefaultSyncContext.getIdentityRef(authorizable);
        return (ref == null) ? null : ref.getProviderName();
    }

    @NotNull
    private static Map<String, ? extends PropertyValue> buildBinding(@NotNull String idpName) {
        String val;
        // idp-name is stored as trailing end after external id followed by ';' => add leading % to the binding
        StringBuilder sb = new StringBuilder();
        sb.append("%;");
        sb.append(idpName.replace("%", "\\%").replace("_", "\\_"));
        val = sb.toString();
        return Collections.singletonMap(BINDING_AUTHORIZABLE_IDS, PropertyValues.newString(val));
    }
    
    private static class InheritedMembershipIterator extends AbstractLazyIterator<Group> {

        private final Iterator<Group> groupIterator;
        private final List<Iterator<Group>> inherited = new ArrayList<>();
        private Iterator<Group> inheritedIterator = null;
        
        private InheritedMembershipIterator(Iterator<Group> groupIterator) {
            this.groupIterator = groupIterator;
        }
        
        @Override
        protected Group getNext() {
            if (groupIterator.hasNext()) {
                Group gr = groupIterator.next();
                try {
                    // call 'memberof' to cover nested inheritance
                    Iterator<Group> it = gr.memberOf();
                    if (it.hasNext()) {
                        inherited.add(it);
                    }
                } catch (RepositoryException e) {
                    log.error("Failed to retrieve membership of group {}", gr, e);
                }
                return gr;
            }
            
            if (inheritedIterator == null || !inheritedIterator.hasNext()) {
                inheritedIterator = getNextInheritedIterator();
            }
            
            if (inheritedIterator.hasNext()) {
                return inheritedIterator.next();
            } else {
                // all inherited groups have been processed
                return null;
            }
        }
        
        @NotNull
        private Iterator<Group> getNextInheritedIterator() {
            if (inherited.isEmpty()) {
                // no more inherited groups to retrieve
                return Iterators.emptyIterator();
            } else {
                // no need to verify if the inherited iterator has any elements as this has been asserted before
                // adding it to the list.
                return inherited.remove(0);
            }
        }
    }
}