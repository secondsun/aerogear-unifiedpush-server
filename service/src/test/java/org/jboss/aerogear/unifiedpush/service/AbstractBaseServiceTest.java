/**
 * JBoss, Home of Professional Open Source
 * Copyright Red Hat, Inc., and individual contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jboss.aerogear.unifiedpush.service;

import org.jboss.aerogear.unifiedpush.service.impl.PushApplicationServiceImpl;
import org.jboss.aerogear.unifiedpush.service.impl.PushSearchByDeveloperServiceImpl;
import org.jboss.aerogear.unifiedpush.service.impl.SearchManager;
import org.jboss.weld.environment.se.Weld;
import org.jboss.weld.environment.se.WeldContainer;
import org.junit.Before;
import org.junit.runner.RunWith;
import org.junit.runners.BlockJUnit4ClassRunner;
import org.junit.runners.model.InitializationError;
import org.keycloak.KeycloakPrincipal;
import org.keycloak.KeycloakSecurityContext;
import org.keycloak.representations.AccessToken;
import org.mockito.Mock;

import javax.annotation.PreDestroy;
import javax.ejb.Stateful;
import javax.enterprise.context.SessionScoped;
import javax.enterprise.inject.Produces;
import javax.inject.Inject;
import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.Persistence;
import javax.servlet.http.HttpServletRequest;
import java.io.Serializable;

import static org.mockito.Mockito.when;

@RunWith(AbstractBaseServiceTest.WeldJUnit4Runner.class)
public abstract class AbstractBaseServiceTest {

    @Mock
    protected HttpServletRequest httpServletRequest;

    @Mock
    protected KeycloakSecurityContext context;

    @Mock
    protected KeycloakPrincipal keycloakPrincipal;

    @Inject
    protected SearchManager searchManager;

    @Inject
    protected PushApplicationServiceImpl pushApplicationService;

    @Inject
    protected PushSearchByDeveloperServiceImpl searchApplicationService;

    // ===================== JUnit hooks =====================

    /**
     * Basic setup stuff, needed for all the UPS related service classes
     */
    @Before
    public void setUp() {
        // Keycloak test environment
        AccessToken token = new AccessToken();
        // The current developer will always be the admin in this testing scenario
        token.setPreferredUsername("admin");
        when(context.getToken()).thenReturn(token);
        when(keycloakPrincipal.getKeycloakSecurityContext()).thenReturn(context);
        when(httpServletRequest.getUserPrincipal()).thenReturn(keycloakPrincipal);

        // glue it to serach mgr
        searchManager.setHttpServletRequest(httpServletRequest);

        // more to setup ?
        specificSetup();
    }

    /**
     * Enforced to override to make sure test-case specific setup is done inside
     * here!
     */
    protected abstract void specificSetup();

    /**
     * Static class to have OpenEJB produce/lookup a test EntityManager.
     */
    @SessionScoped
    @Stateful
    public static class EntityManagerProducer implements Serializable {

        {
            EntityManagerFactory emf = Persistence.createEntityManagerFactory("UnifiedPush");
            entityManager = emf.createEntityManager();
        }

        private static EntityManager entityManager;

        @Produces
        public EntityManager produceEm() {

            if (!entityManager.getTransaction().isActive()) {
                entityManager.getTransaction().begin();
            }

            return entityManager;
        }

        @PreDestroy
        public void closeEntityManager() {
            if (entityManager.isOpen()) {
                entityManager.getTransaction().commit();
                entityManager.close();
            }
        }
    }

    public static class WeldJUnit4Runner extends BlockJUnit4ClassRunner {

        public WeldJUnit4Runner(Class<Object> clazz) throws InitializationError {
            super(clazz);
        }
    
        @Override
        protected Object createTest() {
            final Class<?> test = getTestClass().getJavaClass();
            return WeldContext.INSTANCE.getBean(test);
        }
    }

    public static class WeldContext {

        public static final WeldContext INSTANCE = new WeldContext();
    
        private final Weld weld;
        private final WeldContainer container;
    
        private WeldContext() {
            this.weld = new Weld();
            this.container = weld.initialize();
            Runtime.getRuntime().addShutdownHook(new Thread() {
                @Override
                public void run() {
                    weld.shutdown();
                }
            });
        }
    
        public <T> T getBean(Class<T> type) {
            return container.instance().select(type).get();
        }
    }
}
