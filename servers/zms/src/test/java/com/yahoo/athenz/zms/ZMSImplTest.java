/*
 * Copyright 2016 Yahoo Inc.
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
package com.yahoo.athenz.zms;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.PrivateKey;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.base.Strings;
import com.wix.mysql.EmbeddedMysql;
import com.yahoo.athenz.auth.ServerPrivateKey;
import com.yahoo.athenz.auth.impl.*;
import com.yahoo.athenz.common.metrics.Metric;
import com.yahoo.athenz.common.server.notification.Notification;
import com.yahoo.athenz.common.server.notification.NotificationManager;
import com.yahoo.athenz.zms.notification.PutRoleMembershipNotificationTask;
import com.yahoo.athenz.zms.status.MockStatusCheckerThrowException;
import com.yahoo.athenz.zms.status.MockStatusCheckerNoException;
import com.yahoo.athenz.zms.store.ObjectStoreConnection;
import org.mockito.Mockito;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.testng.annotations.*;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.fail;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.core.EntityTag;
import javax.ws.rs.core.Response;

import com.yahoo.athenz.auth.Authority;
import com.yahoo.athenz.auth.Principal;
import com.yahoo.athenz.auth.token.PrincipalToken;
import com.yahoo.athenz.auth.util.Crypto;
import com.yahoo.athenz.common.server.log.AuditLogMsgBuilder;
import com.yahoo.athenz.common.server.log.AuditLogger;
import com.yahoo.athenz.common.server.log.impl.DefaultAuditLogMsgBuilder;
import com.yahoo.athenz.common.server.log.impl.DefaultAuditLogger;
import com.yahoo.athenz.common.utils.SignUtils;
import com.yahoo.athenz.zms.ZMSImpl.AccessStatus;
import com.yahoo.athenz.zms.ZMSImpl.AthenzObject;
import com.yahoo.athenz.zms.store.AthenzDomain;
import com.yahoo.athenz.zms.utils.ZMSUtils;
import com.yahoo.rdl.Schema;
import com.yahoo.rdl.Struct;
import com.yahoo.rdl.Timestamp;

public class ZMSImplTest {

    public static final String ZMS_PROP_PUBLIC_KEY = "athenz.zms.publickey";

    private ZMSImpl zms             = null;
    private String adminUser        = null;
    private String pubKey           = null; // assume default is K0
    private String pubKeyK1         = null;
    private String pubKeyK2         = null;
    private String privKey          = null; // assume default is K0
    private String privKeyK1        = null;
    private String privKeyK2        = null;
    private final String auditRef   = "audittest";

    // typically used when creating and deleting domains with all the tests
    //
    @Mock private RsrcCtxWrapper mockDomRsrcCtx;
    @Mock private com.yahoo.athenz.common.server.rest.ResourceContext mockDomRestRsrcCtx;
    private AuditLogger auditLogger = null; // default audit logger

    private static final String MOCKCLIENTADDR = "10.11.12.13";
    private static final String DB_USER = "admin";
    private static final String DB_PASS = "unit-test";
    
    @Mock private HttpServletRequest mockServletRequest;
    @Mock private HttpServletResponse mockServletResponse;
    @Mock private NotificationManager mockNotificationManager;

    private static final Struct TABLE_PROVIDER_ROLE_ACTIONS = new Struct()
            .with("admin", "*").with("writer", "WRITE").with("reader", "READ");

    private static final Struct RESOURCE_PROVIDER_ROLE_ACTIONS = new Struct()
            .with("writer", "WRITE").with("reader", "READ");

    private static final int BASE_PRODUCT_ID = 400000000; // these product ids will lie in 400 million range
    private static final java.util.Random domainProductId = new java.security.SecureRandom();
    private static synchronized int getRandomProductId() {
        return BASE_PRODUCT_ID + domainProductId.nextInt(99999999);
    }
    private EmbeddedMysql mysqld;

    static class TestAuditLogger implements AuditLogger {

        final List<String> logMsgList = new ArrayList<>();

        List<String> getLogMsgList() {
            return logMsgList;
        }

        public void log(String logMsg, String msgVersionTag) {
            logMsgList.add(logMsg);
        }
        public void log(AuditLogMsgBuilder msgBldr) {
            String msg = msgBldr.build();
            logMsgList.add(msg);
        }
        @Override
        public AuditLogMsgBuilder getMsgBuilder() {
            return new DefaultAuditLogMsgBuilder();
        }
    }

    @BeforeClass
    public void startMemoryMySQL() {
        mysqld = ZMSTestUtils.startMemoryMySQL(DB_USER, DB_PASS);
    }

    @AfterClass
    public void stopMemoryMySQL() {
        ZMSTestUtils.stopMemoryMySQL(mysqld);
    }

    void setDatabaseReadOnlyMode(boolean readOnlyMode) {
        zms.dbService.defaultRetryCount = 3;
        ZMSTestUtils.setDatabaseReadOnlyMode(mysqld, readOnlyMode);
    }

    @BeforeMethod
    public void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);

        System.setProperty(ZMSConsts.ZMS_PROP_OBJECT_STORE_FACTORY_CLASS, "com.yahoo.athenz.zms.store.impl.JDBCObjectStoreFactory");
        System.setProperty(ZMSConsts.ZMS_PROP_JDBC_RW_STORE, "jdbc:mysql://localhost:3310/zms_server");
        System.setProperty(ZMSConsts.ZMS_PROP_JDBC_RW_USER, DB_USER);
        System.setProperty(ZMSConsts.ZMS_PROP_JDBC_RW_PASSWORD, DB_PASS);

        Mockito.when(mockServletRequest.getRemoteAddr()).thenReturn(MOCKCLIENTADDR);
        Mockito.when(mockServletRequest.isSecure()).thenReturn(true);
        Mockito.when(mockServletRequest.getRequestURI()).thenReturn("/zms/v1/request");
        Mockito.when(mockServletRequest.getMethod()).thenReturn("GET");
        
        System.setProperty(ZMSConsts.ZMS_PROP_FILE_NAME, "src/test/resources/zms.properties");
        System.setProperty(ZMSConsts.ZMS_PROP_METRIC_FACTORY_CLASS, ZMSConsts.ZMS_METRIC_FACTORY_CLASS);
        System.setProperty(ZMSConsts.ZMS_PROP_PROVIDER_ENDPOINTS, ".athenzcompany.com");
        System.setProperty(ZMSConsts.ZMS_PROP_MASTER_COPY_FOR_SIGNED_DOMAINS, "true");

        System.setProperty(ZMSConsts.ZMS_PROP_PRIVATE_KEY_STORE_FACTORY_CLASS,
                "com.yahoo.athenz.auth.impl.FilePrivateKeyStoreFactory");
        System.setProperty(FilePrivateKeyStore.ATHENZ_PROP_PRIVATE_KEY, "src/test/resources/unit_test_zms_private.pem");
        System.setProperty(ZMS_PROP_PUBLIC_KEY, "src/test/resources/zms_public.pem");
        System.setProperty(ZMSConsts.ZMS_PROP_DOMAIN_ADMIN, "user.testadminuser");
        System.setProperty(ZMSConsts.ZMS_PROP_AUTHZ_SERVICE_FNAME,
                "src/test/resources/authorized_services.json");
        System.setProperty(ZMSConsts.ZMS_PROP_SOLUTION_TEMPLATE_FNAME,
                "src/test/resources/solution_templates.json");
        System.setProperty(ZMSConsts.ZMS_PROP_NOAUTH_URI_LIST,
                "uri1,uri2,uri3+uri4");
        System.setProperty(ZMSConsts.ZMS_PROP_AUDIT_REF_CHECK_OBJECTS,
                "role,group,policy,service,domain,entity,tenancy,template");
        auditLogger = new DefaultAuditLogger();

        initializeZms();
    }

    @AfterMethod
    public void clearConnections() {
        if (zms != null && zms.objectStore != null) {
            zms.objectStore.clearConnections();
        }
    }

    private com.yahoo.athenz.zms.ResourceContext createResourceContext(Principal prince) {
        return createResourceContext(prince, "someApi");
    }

    private com.yahoo.athenz.zms.ResourceContext createResourceContext(Principal prince, String apiName) {
        com.yahoo.athenz.common.server.rest.ResourceContext rsrcCtx =
                Mockito.mock(com.yahoo.athenz.common.server.rest.ResourceContext.class);
        Mockito.when(rsrcCtx.principal()).thenReturn(prince);
        Mockito.when(rsrcCtx.request()).thenReturn(mockServletRequest);
        Mockito.when(rsrcCtx.response()).thenReturn(mockServletResponse);

        RsrcCtxWrapper rsrcCtxWrapper = Mockito.mock(RsrcCtxWrapper.class);
        Mockito.when(rsrcCtxWrapper.context()).thenReturn(rsrcCtx);
        Mockito.when(rsrcCtxWrapper.principal()).thenReturn(prince);
        Mockito.when(rsrcCtxWrapper.request()).thenReturn(mockServletRequest);
        Mockito.when(rsrcCtxWrapper.response()).thenReturn(mockServletResponse);
        Mockito.when(rsrcCtxWrapper.getApiName()).thenReturn(apiName);

        return rsrcCtxWrapper;
    }

    private ResourceContext createResourceContext(Principal principal, HttpServletRequest request) {
        return createResourceContext(principal, request, "someApi");
    }

    private ResourceContext createResourceContext(Principal principal, HttpServletRequest request, String apiName) {
        if (request == null) {
            return createResourceContext(principal, apiName);
        }

        com.yahoo.athenz.common.server.rest.ResourceContext rsrcCtx =
                Mockito.mock(com.yahoo.athenz.common.server.rest.ResourceContext.class);
        Mockito.when(rsrcCtx.principal()).thenReturn(principal);
        Mockito.when(rsrcCtx.request()).thenReturn(request);
        Mockito.when(rsrcCtx.response()).thenReturn(mockServletResponse);

        RsrcCtxWrapper rsrcCtxWrapper = Mockito.mock(RsrcCtxWrapper.class);
        Mockito.when(rsrcCtxWrapper.context()).thenReturn(rsrcCtx);
        Mockito.when(rsrcCtxWrapper.request()).thenReturn(request);
        Mockito.when(rsrcCtxWrapper.principal()).thenReturn(principal);
        Mockito.when(rsrcCtxWrapper.response()).thenReturn(mockServletResponse);
        Mockito.when(rsrcCtxWrapper.getApiName()).thenReturn(apiName);

        return rsrcCtxWrapper;
    }

    private ZMSImpl zmsInit() {

        Authority principalAuthority = new com.yahoo.athenz.common.server.debug.DebugPrincipalAuthority();
        String unsignedCreds = "v=U1;d=user;n=user1";
        // used with the mockDomRestRsrcCtx
        final Principal rsrcPrince = SimplePrincipal.create("user", "user1", unsignedCreds + ";s=signature",
                0, principalAuthority);
        assertNotNull(rsrcPrince);
        ((SimplePrincipal) rsrcPrince).setUnsignedCreds(unsignedCreds);
        
        Mockito.when(mockDomRestRsrcCtx.request()).thenReturn(mockServletRequest);
        Mockito.when(mockDomRestRsrcCtx.principal()).thenReturn(rsrcPrince);
        Mockito.when(mockDomRsrcCtx.context()).thenReturn(mockDomRestRsrcCtx);
        Mockito.when(mockDomRsrcCtx.request()).thenReturn(mockServletRequest);
        Mockito.when(mockDomRsrcCtx.principal()).thenReturn(rsrcPrince);
        Mockito.when(mockDomRsrcCtx.getApiName()).thenReturn("someApiMethod");
        Mockito.when(mockDomRsrcCtx.getHttpMethod()).thenReturn("GET");

        String pubKeyName = System.getProperty(ZMS_PROP_PUBLIC_KEY);
        File pubKeyFile = new File(pubKeyName);
        pubKey = Crypto.encodedFile(pubKeyFile);
        
        String privKeyName = System.getProperty(FilePrivateKeyStore.ATHENZ_PROP_PRIVATE_KEY);
        File privKeyFile = new File(privKeyName);
        privKey = Crypto.encodedFile(privKeyFile);

        adminUser = System.getProperty(ZMSConsts.ZMS_PROP_DOMAIN_ADMIN);

        ZMSImpl zmsObj = new ZMSImpl();
        zmsObj.serverPublicKeyMap.put("1", pubKeyK1);
        zmsObj.serverPublicKeyMap.put("2", pubKeyK2);
        ZMSImpl.serverHostName = "localhost";
        zmsObj.notificationManager = mockNotificationManager;

        return zmsObj;
    }

    private void loadServerPublicKeys(ZMSImpl zmsImpl) {
        zmsImpl.serverPublicKeyMap.put("0", pubKey);
        zmsImpl.serverPublicKeyMap.put("1", pubKeyK1);
        zmsImpl.serverPublicKeyMap.put("2", pubKeyK2);
    }

    private ZMSImpl getZmsImpl(AuditLogger alogger) {

        ZMSImpl zmsObj = new ZMSImpl();
        zmsObj.auditLogger = alogger;
        zmsObj.dbService.auditLogger = alogger;
        zmsObj.notificationManager = mockNotificationManager;
        
        ZMSImpl.serverHostName = "localhost";

        ServiceIdentity service = createServiceObject("sys.auth", "zms",
                "http://localhost", "/usr/bin/java", "root", "users", "host1");
        
        zmsObj.putServiceIdentity(mockDomRsrcCtx, "sys.auth", "zms", auditRef, service);
        return zmsObj;
    }

    private void initializeZms() throws IOException {

        Path path = Paths.get("./src/test/resources/zms_public_k1.pem");
        pubKeyK1 = Crypto.ybase64((new String(Files.readAllBytes(path))).getBytes());

        path = Paths.get("./src/test/resources/zms_public_k2.pem");
        pubKeyK2 = Crypto.ybase64(new String(Files.readAllBytes(path)).getBytes());

        path = Paths.get("./src/test/resources/unit_test_zms_private_k1.pem");
        privKeyK1 = Crypto.ybase64(new String(Files.readAllBytes(path)).getBytes());
 
        path = Paths.get("./src/test/resources/unit_test_zms_private_k2.pem");
        privKeyK2 = Crypto.ybase64(new String(Files.readAllBytes(path)).getBytes());

        zms = zmsInit();
    }

    private Membership generateMembership(String roleName, String memberName) {
        return generateMembership(roleName, memberName, null);
    }
    
    private Membership generateMembership(String roleName, String memberName,
            Timestamp expiration) {
        Membership mbr = new Membership();
        mbr.setRoleName(roleName);
        mbr.setMemberName(memberName);
        mbr.setIsMember(true);
        mbr.setExpiration(expiration);
        return mbr;
    }

    private GroupMembership generateGroupMembership(final String groupName, final String memberName) {
        return generateGroupMembership(groupName, memberName, null);
    }

    private GroupMembership generateGroupMembership(final String groupName, final String memberName,
                                                    Timestamp expiration) {
        GroupMembership mbr = new GroupMembership();
        mbr.setGroupName(groupName);
        mbr.setMemberName(memberName);
        mbr.setIsMember(true);
        mbr.setExpiration(expiration);
        return mbr;
    }

    private TopLevelDomain createTopLevelDomainObject(String name,
            String description, String org, String admin) {

        TopLevelDomain dom = new TopLevelDomain();
        dom.setName(name);
        dom.setDescription(description);
        dom.setOrg(org);
        dom.setYpmId(getRandomProductId());

        List<String> admins = new ArrayList<>();
        admins.add(admin);
        dom.setAdminUsers(admins);

        return dom;
    }

    private UserDomain createUserDomainObject(String name, String description, String org) {

        UserDomain dom = new UserDomain();
        dom.setName(name);
        dom.setDescription(description);
        dom.setOrg(org);

        return dom;
    }
    
    private SubDomain createSubDomainObject(String name, String parent,
            String description, String org, String admin) {

        SubDomain dom = new SubDomain();
        dom.setName(name);
        dom.setDescription(description);
        dom.setOrg(org);
        dom.setParent(parent);

        List<String> admins = new ArrayList<>();
        admins.add(admin);
        dom.setAdminUsers(admins);

        return dom;
    }

    private DomainMeta createDomainMetaObject(String description, String org,
            Boolean enabled, Boolean auditEnabled, String account, Integer productId) {

        DomainMeta meta = new DomainMeta();
        meta.setDescription(description);
        meta.setOrg(org);
        if (enabled != null) {
            meta.setEnabled(enabled);
        }
        if (auditEnabled != null) {
            meta.setAuditEnabled(auditEnabled);
        }
        if (account != null) {
            meta.setAccount(account);
        }
        meta.setYpmId(productId);

        return meta;
    }

    private void checkRoleMember(final List<String> checkList, List<RoleMember> members) {
        boolean found = false;
        for (String roleMemberName: checkList) {
            for (RoleMember roleMember: members) {
                if (roleMember.getMemberName().equals(roleMemberName)){
                    found = true;
                    break;
                }
            }
            if (!found) {
                fail("Member " + roleMemberName + " not found");
            }
        }
    }

    private void checkGroupMember(final List<String> checkList, List<GroupMember> members) {
        boolean found = false;
        for (String groupMemberName: checkList) {
            for (GroupMember groupMember: members) {
                if (groupMember.getMemberName().equals(groupMemberName)){
                    found = true;
                    break;
                }
            }
            if (!found) {
                fail("Member " + groupMemberName + " not found");
            }
        }
    }

    private Group createGroupObject(String domainName, String groupName, String member1, String member2) {

        List<GroupMember> members = new ArrayList<>();
        if (member1 != null) {
            members.add(new GroupMember().setMemberName(member1));
        }
        if (member2 != null) {
            members.add(new GroupMember().setMemberName(member2));
        }
        return createGroupObject(domainName, groupName, members);
    }

    private Group createGroupObject(String domainName, String groupName, List<GroupMember> members) {

        Group group = new Group();
        group.setName(ZMSUtils.groupResourceName(domainName, groupName));
        group.setGroupMembers(members);
        return group;
    }

    private Role createRoleObject(String domainName, String roleName,
            String trust) {
        Role role = new Role();
        role.setName(ZMSUtils.roleResourceName(domainName, roleName));
        role.setTrust(trust);
        return role;
    }

    private Role createRoleObject(String domainName, String roleName,
            String trust, String member1, String member2) {

        List<RoleMember> members = new ArrayList<>();
        if (member1 != null) {
            members.add(new RoleMember().setMemberName(member1));
        }
        if (member2 != null) {
            members.add(new RoleMember().setMemberName(member2));
        }
        return createRoleObject(domainName, roleName, trust, members);
    }

    private Role createRoleObject(String domainName, String roleName,
            String trust, List<RoleMember> members) {
        
        Role role = new Role();
        role.setName(ZMSUtils.roleResourceName(domainName, roleName));
        role.setRoleMembers(members);
        if (trust != null) {
            role.setTrust(trust);
        }
        
        return role;
    }
    
    private Policy createPolicyObject(String domainName, String policyName,
            String roleName, String action,  String resource, 
            AssertionEffect effect) {
        return createPolicyObject(domainName, policyName, roleName, true,
                action, resource, effect);
    }
    
    private Policy createPolicyObject(String domainName, String policyName,
            String roleName, boolean generateRoleName, String action, 
            String resource, AssertionEffect effect) {

        Policy policy = new Policy();
        policy.setName(ZMSUtils.policyResourceName(domainName, policyName));

        Assertion assertion = new Assertion();
        assertion.setAction(action);
        assertion.setEffect(effect);
        assertion.setResource(resource);
        if (generateRoleName) {
            assertion.setRole(ZMSUtils.roleResourceName(domainName, roleName));
        } else {
            assertion.setRole(roleName);
        }

        List<Assertion> assertList = new ArrayList<>();
        assertList.add(assertion);

        policy.setAssertions(assertList);
        return policy;
    }

    private Policy createPolicyObject(String domainName, String policyName) {
        return createPolicyObject(domainName, policyName, "Role1", "*",
                domainName + ":*", AssertionEffect.ALLOW);
    }
    
    private ServiceIdentity createServiceObject(String domainName,
                            String serviceName, String endPoint, String executable,
                            String user, String group, String host) {

        ServiceIdentity service = new ServiceIdentity();
        service.setExecutable(executable);
        service.setName(ZMSUtils.serviceResourceName(domainName, serviceName));

        List<PublicKeyEntry> publicKeyList = new ArrayList<>();
        PublicKeyEntry publicKeyEntry1 = new PublicKeyEntry();
        publicKeyEntry1.setKey(pubKeyK1);
        publicKeyEntry1.setId("1");
        publicKeyList.add(publicKeyEntry1);
        PublicKeyEntry publicKeyEntry2 = new PublicKeyEntry();
        publicKeyEntry2.setKey(pubKeyK2);
        publicKeyEntry2.setId("2");
        publicKeyList.add(publicKeyEntry2);
        service.setPublicKeys(publicKeyList);

        service.setUser(user);
        service.setGroup(group);

        if (endPoint != null) {
            service.setProviderEndpoint(endPoint);
        }

        List<String> hosts = new ArrayList<>();
        hosts.add(host);
        service.setHosts(hosts);

        return service;
    }
    
    private Entity createEntityObject(String entityName) {

        Entity entity = new Entity();
        entity.setName(entityName);

        Struct value = new Struct();
        value.put("Key1", "Value1");
        entity.setValue(value);

        return entity;
    }
    
    private void setupTenantDomainProviderService(ZMSImpl zms, String tenantDomain, String providerDomain,
            String providerService, String providerEndpoint) {

        // create domain for tenant
        //
        TopLevelDomain dom1 = createTopLevelDomainObject(tenantDomain,
                "Test Tenant Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        // create domain for provider
        //
        TopLevelDomain domProv = createTopLevelDomainObject(providerDomain,
                "Test Provider Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, domProv);

        // create service identity for providerDomain.providerService
        //
        ServiceIdentity service = createServiceObject(
                providerDomain, providerService, providerEndpoint,
                "/usr/bin/java", "root", "users", "localhost");

        zms.putServiceIdentity(mockDomRsrcCtx, providerDomain, providerService, auditRef, service);
    }

    private void setupPrincipalSystemMetaDelete(ZMSImpl zms, final String principal,
            final String domainName, final String ...attributeNames) {

        Role role = createRoleObject("sys.auth", "metaadmin", null, principal, null);
        zms.putRole(mockDomRsrcCtx, "sys.auth", "metaadmin", auditRef, role);

        Policy policy = new Policy();
        policy.setName("metaadmin");

        List<Assertion> assertList = new ArrayList<>();
        Assertion assertion;

        for (String attributeName : attributeNames) {
            assertion = new Assertion();
            assertion.setAction("delete");
            assertion.setEffect(AssertionEffect.ALLOW);
            assertion.setResource("sys.auth:meta.domain." + attributeName + "." + domainName);
            assertion.setRole("sys.auth:role.metaadmin");
            assertList.add(assertion);
        }

        policy.setAssertions(assertList);

        zms.putPolicy(mockDomRsrcCtx, "sys.auth", "metaadmin", auditRef, policy);
    }

    private void cleanupPrincipalSystemMetaDelete(ZMSImpl zms) {

        zms.deleteRole(mockDomRsrcCtx, "sys.auth", "metaadmin", auditRef);
        zms.deletePolicy(mockDomRsrcCtx, "sys.auth", "metaadmin", auditRef);
    }

    private void setupTenantDomainProviderService(String tenantDomain, String providerDomain,
            String providerService, String providerEndpoint) {
        setupTenantDomainProviderService(zms, tenantDomain, providerDomain, providerService, providerEndpoint);
    }
    
    private Tenancy createTenantObject(String domain, String service) {
        
        Tenancy tenant = new Tenancy();
        tenant.setDomain(domain);
        tenant.setService(service);
        
        return tenant;
    }

    private boolean verifyRoleMember(Role role, final String memberName) {
        for (RoleMember roleMember : role.getRoleMembers()) {
            if (roleMember.getMemberName().equals(memberName)) {
                return true;
            }
        }
        return false;
    }

    @Test
    public void testSchema() {
        Schema schema = zms.schema();
        assertNotNull(schema);
    }

    @Test
    public void testGetAuditLogMsgBuilder() {
        AuditLogMsgBuilder msgBldr = ZMSUtils.getAuditLogMsgBuilder(mockDomRsrcCtx, auditLogger,
                "mydomain", auditRef, "myapi", "PUT");
        assertNotNull(msgBldr);
    }

    @Test
    public void testGetAuditLogMsgBuilderNullCtx() {
        AuditLogMsgBuilder msgBldr = ZMSUtils.getAuditLogMsgBuilder(null, auditLogger,
                "mydomain", auditRef, "myapi", "PUT");
        assertNotNull(msgBldr);
    }

    @Test
    public void testGetAuditLogMsgBuilderNullPrincipal() {
        ResourceContext ctx = createResourceContext(null);
        AuditLogMsgBuilder msgBldr = ZMSUtils.getAuditLogMsgBuilder(ctx, auditLogger,
                "mydomain", auditRef, "myapi", "PUT");
        assertNotNull(msgBldr);
    }

    @Test
    public void testGetAuditLogMsgBuilderTokenWithSig() {
        Authority principalAuthority = new com.yahoo.athenz.common.server.debug.DebugPrincipalAuthority();
        String userId     = "user1";
        String signature = "ABRACADABRA";
        String unsignedCreds = "v=U1;d=user;n=user1";
        Principal principal = SimplePrincipal.create("user", userId, unsignedCreds + ";s=" + signature,
                0, principalAuthority);
        assertNotNull(principal);
        ((SimplePrincipal) principal).setUnsignedCreds(unsignedCreds); // set unsigned creds
        ResourceContext ctx = createResourceContext(principal);
        AuditLogMsgBuilder msgBldr = ZMSUtils.getAuditLogMsgBuilder(ctx, auditLogger,
                "mydomain", auditRef, "myapi", "PUT");
        assertNotNull(msgBldr);
        String who = msgBldr.who();
        assertNotNull(who);
        assertTrue(who.contains(userId));
        assertFalse(who.contains(signature), "Should not contain the signature: " + who);
    }

    @Test
    public void testGetAuditLogMsgBuilderTokenSigMissing() {
        Authority principalAuthority = new com.yahoo.athenz.common.server.debug.DebugPrincipalAuthority();
        String userId     = "user1";
        String unsignedCreds = "v=U1;d=user;n=user1";
        Principal principal = SimplePrincipal.create("user", userId, unsignedCreds,
                0, principalAuthority);
        ResourceContext ctx = createResourceContext(principal);
        AuditLogMsgBuilder msgBldr = ZMSUtils.getAuditLogMsgBuilder(ctx, auditLogger,
                "mydomain", auditRef, "myapi", "PUT");
        assertNotNull(msgBldr);
        String who = msgBldr.who();
        assertNotNull(who);
        assertTrue(who.contains(userId));
    }

    @Test
    public void testGetAuditLogMsgBuilderNullParams() {
        AuditLogMsgBuilder msgBldr = ZMSUtils.getAuditLogMsgBuilder(mockDomRsrcCtx, auditLogger,
                null, null, null, null);
        assertNotNull(msgBldr);
    }

    @Test
    public void testGetDomain() {
        Domain domain = zms.getDomain(mockDomRsrcCtx, "sys.auth");
        assertNotNull(domain);
    }
    
    @Test
    public void testGetDomainThrowException() {
        try {
            zms.getDomain(mockDomRsrcCtx, "wrongDomain");
            fail("notfounderror not thrown.");
        } catch (ResourceException e) {
            assertEquals(e.getCode(), 404);
        }
    }

    @Test
    public void testPostDomain() {
        String domName = "olddominion";
        try {
            zms.deleteTopLevelDomain(mockDomRsrcCtx, domName, auditRef);
        } catch (ResourceException re) {
            assertEquals(re.getCode(), 404);
        }

        TopLevelDomain dom = new TopLevelDomain();
        dom.setName(domName);
        dom.setDescription("old virginny");
        dom.setOrg("universities");
        dom.setYpmId(1930);

        List<String> admins = new ArrayList<>();
        admins.add(adminUser);
        dom.setAdminUsers(admins);

        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom);

        // post subdomain
        String subDomName = "extension";
        SubDomain subDom = createSubDomainObject(subDomName, domName,
                "old dominion extension", "education", adminUser);
        zms.postSubDomain(mockDomRsrcCtx, domName, auditRef, subDom);

        // post sub domain that is too big - default length is 128
        String subDomNameBad = "extension0extension0extension0extension0";
        subDomNameBad = subDomNameBad.concat("extension0extension0extension0extension0");
        subDomNameBad = subDomNameBad.concat("extension0extension0extension0extension0");

        subDom = createSubDomainObject(subDomNameBad, domName,
                "old dominion extension+++", "education", adminUser);
        try {
            zms.postSubDomain(mockDomRsrcCtx, domName, auditRef, subDom);
            fail("requesterror not thrown.");
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 400);
            assertTrue(ex.getMessage().contains("Invalid SubDomain name"));
            assertTrue(ex.getMessage().contains("name length cannot exceed"));
        }

        zms.deleteSubDomain(mockDomRsrcCtx, domName, subDomName, auditRef);
        zms.deleteTopLevelDomain(mockDomRsrcCtx, domName, auditRef);
    }

    @Test
    public void testPostDomainNullObject() {
        try {
            zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, null);
            fail("requesterror not thrown.");
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 400);
        }
    }

    @Test
    public void testPostTopLevelDomainNoProductId() {
        
        // enable product id support
        
        System.setProperty(ZMSConsts.ZMS_PROP_PRODUCT_ID_SUPPORT, "true");
        ZMSImpl zmsImpl = zmsInit();
        
        String domName = "jabberwocky";
        try {
            zmsImpl.deleteTopLevelDomain(mockDomRsrcCtx, domName, auditRef);
        } catch (ResourceException re) {
            assertEquals(re.getCode(), 404);
        }

        TopLevelDomain dom = new TopLevelDomain();
        dom.setName(domName);
        dom.setDescription("mythic animal");
        dom.setOrg("animals");

        List<String> admins = new ArrayList<>();
        admins.add(adminUser);
        dom.setAdminUsers(admins);

        try {
            zmsImpl.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom);
            fail("requesterror not thrown.");
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 400);
            assertTrue(ex.getMessage().contains("Product Id is required when creating top level domain"));
        }

        System.clearProperty(ZMSConsts.ZMS_PROP_PRODUCT_ID_SUPPORT);
        zmsImpl.objectStore.clearConnections();
    }

    @Test
    public void testPostTopLevelDomainNameTooLong() {

        // have 129 chars - default is 128

        final String domName = Strings.repeat("a", 129);

        try {
            zms.deleteTopLevelDomain(mockDomRsrcCtx, domName, auditRef);
        } catch (ResourceException re) {
            assertEquals(re.getCode(), 404);
        }

        TopLevelDomain dom = new TopLevelDomain();
        dom.setName(domName);
        dom.setDescription("bigun");
        dom.setOrg("bigdog");

        List<String> admins = new ArrayList<>();
        admins.add(adminUser);
        dom.setAdminUsers(admins);

        try {
            zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom);
            fail("requesterror not thrown.");
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 400);
            assertTrue(ex.getMessage().contains("name length cannot exceed"));
        }
    }

    @Test
    public void testPostDomainNameOnSizeLimit() {

        // set the domain size limit to 45
        System.setProperty(ZMSConsts.ZMS_PROP_DOMAIN_NAME_MAX_SIZE, "45");

        ZMSImpl zmsImpl = zmsInit();

        // have 45 chars
        final String domName = Strings.repeat("a", 45);

        try {
            zmsImpl.deleteTopLevelDomain(mockDomRsrcCtx, domName, auditRef);
        } catch (ResourceException re) {
            assertEquals(re.getCode(), 404);
        }

        TopLevelDomain dom = new TopLevelDomain();
        dom.setName(domName);
        dom.setDescription("bigun");
        dom.setOrg("bigdog");
        dom.setYpmId(999999);

        List<String> admins = new ArrayList<>();
        admins.add(adminUser);
        dom.setAdminUsers(admins);

        zmsImpl.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom);

        // post sub domain which will be too big by 1 char
        String subDomNameBad = "B";
        SubDomain subDom = createSubDomainObject(subDomNameBad, domName,
                "1 char too many", "dogs", adminUser);
        try {
            zmsImpl.postSubDomain(mockDomRsrcCtx, domName, auditRef, subDom);
            fail("requesterror not thrown.");
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 400);
            assertTrue(ex.getMessage().contains("Invalid SubDomain name"));
            assertTrue(ex.getMessage().contains("name length cannot exceed"));
        }

        zmsImpl.deleteTopLevelDomain(mockDomRsrcCtx, domName, auditRef);
        System.clearProperty(ZMSConsts.ZMS_PROP_DOMAIN_NAME_MAX_SIZE);
        zmsImpl.objectStore.clearConnections();
    }

    @Test
    public void testPostTopLevelDomainNameReduceSizeLimitTooSmall() {

        // lower name length to 5 which should be reset internally to the default
        System.setProperty(ZMSConsts.ZMS_PROP_DOMAIN_NAME_MAX_SIZE, "5");
        ZMSImpl zmsImpl = zmsInit();

        String domName = "abcdef7890";
        try {
            zmsImpl.deleteTopLevelDomain(mockDomRsrcCtx, domName, auditRef);
        } catch (ResourceException re) {
            assertEquals(re.getCode(), 404);
        }

        TopLevelDomain dom = new TopLevelDomain();
        dom.setName(domName);
        dom.setDescription("bigun");
        dom.setOrg("bigdog");
        dom.setYpmId(77777);

        List<String> admins = new ArrayList<>();
        admins.add(adminUser);
        dom.setAdminUsers(admins);

        zmsImpl.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom);
        zmsImpl.deleteTopLevelDomain(mockDomRsrcCtx, domName, auditRef);
        System.clearProperty(ZMSConsts.ZMS_PROP_DOMAIN_NAME_MAX_SIZE);
        zmsImpl.objectStore.clearConnections();
    }

    @Test
    public void testGetDomainList() {

        TopLevelDomain dom1 = createTopLevelDomainObject("ListDom1",
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        TopLevelDomain dom2 = createTopLevelDomainObject("ListDom2",
                "Test Domain2", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom2);

        DomainList domList = zms.getDomainList(mockDomRsrcCtx, null, null, null, null,
                null, null, null, null, null);
        assertNotNull(domList);

        assertTrue(domList.getNames().contains("ListDom1".toLowerCase()));
        assertTrue(domList.getNames().contains("ListDom2".toLowerCase()));

        zms.deleteTopLevelDomain(mockDomRsrcCtx, "ListDom1", auditRef);
        zms.deleteTopLevelDomain(mockDomRsrcCtx, "ListDom2", auditRef);
    }

    @Test
    public void testGetDomainListByAccount() {
        
        String domainName = "lookupdomainaccount";
        
        TopLevelDomain dom1 = createTopLevelDomainObject(domainName,
                "Test Domain1", "testOrg", adminUser);
        dom1.setAccount("1234");
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        DomainList domList = zms.getDomainList(mockDomRsrcCtx, null, null, null, null,
                "1234", null, null, null, null);
        assertNotNull(domList.getNames());
        assertEquals(domList.getNames().size(), 1);
        assertEquals(domList.getNames().get(0), domainName);
        
        domList = zms.getDomainList(mockDomRsrcCtx, null, null, null, null,
                "1235", null, null, null, null);
        assertNull(domList.getNames());
        
        zms.deleteTopLevelDomain(mockDomRsrcCtx, domainName, auditRef);
    }
    
    @Test
    public void testGetDomainListByProductId() {
        
        String domainName = "lookupdomainbyproductid";
        
        // enable product id support
        
        System.setProperty(ZMSConsts.ZMS_PROP_PRODUCT_ID_SUPPORT, "true");
        ZMSImpl zmsImpl = zmsInit();
        
        TopLevelDomain dom1 = createTopLevelDomainObject(domainName,
                "Test Domain1", "testOrg", adminUser);
        dom1.setYpmId(101);
        zmsImpl.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        DomainList domList = zmsImpl.getDomainList(mockDomRsrcCtx, null, null, null,
                null, null, 101, null, null, null);
        assertNotNull(domList.getNames());
        assertEquals(domList.getNames().size(), 1);
        assertEquals(domList.getNames().get(0), domainName);
        
        domList = zmsImpl.getDomainList(mockDomRsrcCtx, null, null, null, null, null,
                102, null, null, null);
        assertNull(domList.getNames());
        
        zmsImpl.deleteTopLevelDomain(mockDomRsrcCtx, domainName, auditRef);
        System.clearProperty(ZMSConsts.ZMS_PROP_PRODUCT_ID_SUPPORT);
        zmsImpl.objectStore.clearConnections();
    }
    
    @Test
    public void testGetDomainListIfModifiedSince() {

        TopLevelDomain dom1 = createTopLevelDomainObject("ListDom1",
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        // let's get the current time
        
        Date now = new Date();

        ZMSUtils.threadSleep(1000);

        TopLevelDomain dom2 = createTopLevelDomainObject("ListDom2",
                "Test Domain2", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom2);
        
        DateFormat df = new SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss zzz");
        String modifiedSince = df.format(now);
     
        // this is only a partial list since our file struct store
        // which the unit tests use does not support last modified
        // option so this will be tested in zms_system_test package
        
        DomainList domList = zms.getDomainList(mockDomRsrcCtx, null, null, null,
                null, null, null, null, null, modifiedSince);
        assertNotNull(domList);

        assertTrue(domList.getNames().contains("ListDom2".toLowerCase()));

        zms.deleteTopLevelDomain(mockDomRsrcCtx, "ListDom1", auditRef);
        zms.deleteTopLevelDomain(mockDomRsrcCtx, "ListDom2", auditRef);
    }
    
    @Test
    public void testGetDomainListInvalidIfModifiedSince() {

        try {
            zms.getDomainList(mockDomRsrcCtx, null, null, null, null, null,
                    null, null, null, "abc");
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 400);
        }
        
        try {
            zms.getDomainList(mockDomRsrcCtx, null, null, null, null, null,
                    null, null, null, "May 20, 1099");
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 400);
        }
        
        try {
            zms.getDomainList(mockDomRsrcCtx, null, null, null, null, null,
                    null, null, null, "03:03:20 PM");
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 400);
        }
    }
    
    @Test
    public void testGetDomainListParamsLimit() {

        TopLevelDomain dom1 = createTopLevelDomainObject("LimitDom1",
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        TopLevelDomain dom2 = createTopLevelDomainObject("LimitDom2",
                "Test Domain2", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom2);

        DomainList domList = zms.getDomainList(mockDomRsrcCtx, 1, null, null,
                null, null, null, null, null, null);
        assertEquals(1, domList.getNames().size());

        zms.deleteTopLevelDomain(mockDomRsrcCtx, "LimitDom1", auditRef);
        zms.deleteTopLevelDomain(mockDomRsrcCtx, "LimitDom2", auditRef);
    }

    @Test
    public void testGetDomainListParamsSkip() {

        TopLevelDomain dom1 = createTopLevelDomainObject("SkipDom1",
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        TopLevelDomain dom2 = createTopLevelDomainObject("SkipDom2",
                "Test Domain2", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom2);

        TopLevelDomain dom3 = createTopLevelDomainObject("SkipDom3",
                "Test Domain3", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom3);

        DomainList domList = zms.getDomainList(mockDomRsrcCtx, null, null, null,
                null, null, null, null, null, null);
        int size = domList.getNames().size();
        assertTrue(size > 3);

        // ask for only for 2 domains back
        domList = zms.getDomainList(mockDomRsrcCtx, 2, null, null, null, null,
                null, null, null, null);
        assertEquals(domList.getNames().size(), 2);

        // ask for the remaining domains
        DomainList remList = zms.getDomainList(mockDomRsrcCtx, null, domList.getNext(),
                null, null, null, null, null, null, null);
        assertEquals(remList.getNames().size(), size - 2);

        zms.deleteTopLevelDomain(mockDomRsrcCtx, "SkipDom1", auditRef);
        zms.deleteTopLevelDomain(mockDomRsrcCtx, "SkipDom2", auditRef);
        zms.deleteTopLevelDomain(mockDomRsrcCtx, "SkipDom3", auditRef);
    }

    @Test
    public void testGetDomainListParamsPrefix() {

        TopLevelDomain dom1 = createTopLevelDomainObject("NoPrefixDom1",
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        TopLevelDomain dom2 = createTopLevelDomainObject("PrefixDom2",
                "Test Domain2", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom2);

        DomainList domList = zms.getDomainList(mockDomRsrcCtx, null, null,
                "Prefix", null, null, null, null, null, null);

        assertFalse(domList.getNames().contains("NoPrefixDom1".toLowerCase()));
        assertTrue(domList.getNames().contains("PrefixDom2".toLowerCase()));

        zms.deleteTopLevelDomain(mockDomRsrcCtx, "NoPrefixDom1", auditRef);
        zms.deleteTopLevelDomain(mockDomRsrcCtx, "PrefixDom2", auditRef);
    }

    @Test
    public void testGetDomainListParamsDepth() {

        TopLevelDomain dom1 = createTopLevelDomainObject("DepthDom1",
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        SubDomain dom2 = createSubDomainObject("DepthDom2", "DepthDom1",
                "Test Domain2", "testOrg", adminUser);
        zms.postSubDomain(mockDomRsrcCtx, "DepthDom1", auditRef, dom2);

        SubDomain dom3 = createSubDomainObject("DepthDom3",
                "DepthDom1.DepthDom2", "Test Domain3", "testOrg", adminUser);
        zms.postSubDomain(mockDomRsrcCtx, "DepthDom1.DepthDom2", auditRef, dom3);

        DomainList domList = zms.getDomainList(mockDomRsrcCtx, null, null, null,
                1, null, null, null, null, null);

        assertTrue(domList.getNames().contains("DepthDom1".toLowerCase()));
        assertTrue(domList.getNames().contains("DepthDom1.DepthDom2".toLowerCase()));
        assertFalse(domList.getNames().contains("DepthDom1.DepthDom2.DepthDom3".toLowerCase()));
        
        zms.deleteSubDomain(mockDomRsrcCtx, "DepthDom1.DepthDom2", "DepthDom3", auditRef);
        zms.deleteSubDomain(mockDomRsrcCtx, "DepthDom1", "DepthDom2", auditRef);
        zms.deleteTopLevelDomain(mockDomRsrcCtx, "DepthDom1", auditRef);
    }
    
    @Test
    public void testGetDomainListThrowException() {
        try {
            zms.getDomainList(mockDomRsrcCtx, -1, null, null, null, null, null, null, null, null);
            fail("requesterror not thrown.");
        } catch (ResourceException e) {
            assertEquals(e.getCode(), 400);
        }
    }

    @Test
    public void testCreateTopLevelDomain() {

        TopLevelDomain dom1 = createTopLevelDomainObject("AddTopDom1",
                "Test Domain1", "testOrg", adminUser);
        Domain resDom1 = zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);
        assertNotNull(resDom1);

        Domain resDom2 = zms.getDomain(mockDomRsrcCtx, "AddTopDom1");
        assertNotNull(resDom2);

        zms.deleteTopLevelDomain(mockDomRsrcCtx, "AddTopDom1", auditRef);
    }

    @Test
    public void testCreateTopLevelDomainOnceOnly() {

        TestAuditLogger alogger = new TestAuditLogger();
        ZMSImpl zmsImpl = getZmsImpl(alogger);

        TopLevelDomain dom1 = createTopLevelDomainObject("AddOnceTopDom1",
                "Test Domain1", "testOrg", adminUser);
        Domain resDom1 = zmsImpl.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);
        assertNotNull(resDom1);

        // we should get an exception for the second call

        try {
            zmsImpl.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);
            fail();
        } catch (Exception ex) {
            assertTrue(true);
        }

        zmsImpl.deleteTopLevelDomain(mockDomRsrcCtx, "AddOnceTopDom1", auditRef);
    }

    @Test
    public void testCreateSubDomain() {

        TopLevelDomain dom1 = createTopLevelDomainObject("AddSubDom1",
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        SubDomain dom2 = createSubDomainObject("AddSubDom2", "AddSubDom1",
                "Test Domain2", null, adminUser);
        Domain resDom1 = zms.postSubDomain(mockDomRsrcCtx, "AddSubDom1", auditRef, dom2);
        assertNotNull(resDom1);

        Domain resDom2 = zms.getDomain(mockDomRsrcCtx, "AddSubDom1.AddSubDom2");
        assertNotNull(resDom2);

        assertEquals(dom2.getOrg(), "testorg");
        assertFalse(resDom2.getAuditEnabled());

        zms.deleteSubDomain(mockDomRsrcCtx, "AddSubDom1", "AddSubDom2", auditRef);
        zms.deleteTopLevelDomain(mockDomRsrcCtx, "AddSubDom1", auditRef);
    }

    @Test
    public void testCreateSubDomainInAuditEnabledParent() {

        TopLevelDomain dom1 = createTopLevelDomainObject("AddSubDom1",
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        DomainMeta meta = createDomainMetaObject("Test Domain1", "testOrg",
                true, true, "12345", 1001);
        zms.putDomainMeta(mockDomRsrcCtx, "AddSubDom1", auditRef, meta);
        zms.putDomainSystemMeta(mockDomRsrcCtx, "AddSubDom1", "auditenabled", auditRef, meta);

        SubDomain dom2 = createSubDomainObject("AddSubDom2", "AddSubDom1",
                "Test Domain2", "testOrg", adminUser);
        Domain resDom1 = zms.postSubDomain(mockDomRsrcCtx, "AddSubDom1", auditRef, dom2);
        assertNotNull(resDom1);

        Domain resDom2 = zms.getDomain(mockDomRsrcCtx, "AddSubDom1.AddSubDom2");
        assertNotNull(resDom2);

        assertTrue(resDom2.getAuditEnabled());

        zms.deleteSubDomain(mockDomRsrcCtx, "AddSubDom1", "AddSubDom2", auditRef);
        zms.deleteTopLevelDomain(mockDomRsrcCtx, "AddSubDom1", auditRef);
    }

    @Test
    public void testCreateUserDomain() {

        UserDomain dom1 = createUserDomainObject("hga", "Test Domain1", "testOrg");
        zms.postUserDomain(mockDomRsrcCtx, "hga", auditRef, dom1);

        Domain resDom2 = zms.getDomain(mockDomRsrcCtx, "user.hga");
        assertNotNull(resDom2);

        zms.deleteUserDomain(mockDomRsrcCtx, "hga", auditRef);
    }
    

    @Test
    public void testCreateUserDomainMismatch() {

        UserDomain dom1 = createUserDomainObject("hga", "Test Domain Mismatch", "testMismatchOrg");
        try {
            zms.postUserDomain(mockDomRsrcCtx, "hga2", auditRef, dom1);
        } catch (ResourceException ex) {
            assertEquals(403, ex.getCode());
        }
    }
    
    @Test
    public void testDeleteUserDomain() {

        UserDomain dom1 = createUserDomainObject("hga", "Test Domain Delete User Domain", "testDeleteOrg");
        zms.postUserDomain(mockDomRsrcCtx, "hga", auditRef, dom1);

        zms.deleteUserDomain(mockDomRsrcCtx, "hga", auditRef);

        try {
            zms.getDomain(mockDomRsrcCtx, "hga");
            fail();
        } catch (Exception ex) {
            assertTrue(true);
        }
    }

    @Test
    public void testCeateSubDomainNoParent() {

        SubDomain dom = createSubDomainObject("sub", "parent",
                "Test Domain", "testOrg", adminUser);
        try {
            zms.postSubDomain(mockDomRsrcCtx, "parent", auditRef, dom);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), ResourceException.NOT_FOUND);
        }

        // now first create the parent

        TopLevelDomain dom1 = createTopLevelDomainObject("parent",
                "Test Domain", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        // and then create the subdomain

        Domain resDom = zms.postSubDomain(mockDomRsrcCtx, "parent", auditRef, dom);
        assertNotNull(resDom);

        // clean up domains

        zms.deleteSubDomain(mockDomRsrcCtx, "parent", "sub", auditRef);
        zms.deleteTopLevelDomain(mockDomRsrcCtx, "parent", auditRef);
    }

    @Test
    public void testCreateSubDomainWithVirtualLimit() {
        
        System.setProperty(ZMSConsts.ZMS_PROP_VIRTUAL_DOMAIN_LIMIT, "2");
        ZMSImpl zmsTest = zmsInit();
        
        TopLevelDomain dom1 = createTopLevelDomainObject("SubDomNoVirtual",
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);
        
        SubDomain dom = createSubDomainObject("sub1", "SubDomNoVirtual",
                "Test Domain2", "testOrg", adminUser);
        Domain resDom = zmsTest.postSubDomain(mockDomRsrcCtx, "SubDomNoVirtual", auditRef, dom);
        assertNotNull(resDom);
        
        dom = createSubDomainObject("sub2", "SubDomNoVirtual",
                "Test Domain2", "testOrg", adminUser);
        resDom = zmsTest.postSubDomain(mockDomRsrcCtx, "SubDomNoVirtual", auditRef, dom);
        assertNotNull(resDom);
        
        dom = createSubDomainObject("sub3", "SubDomNoVirtual",
                "Test Domain2", "testOrg", adminUser);
        resDom = zmsTest.postSubDomain(mockDomRsrcCtx, "SubDomNoVirtual", auditRef, dom);
        assertNotNull(resDom);
        
        zms.deleteSubDomain(mockDomRsrcCtx, "SubDomNoVirtual", "sub3", auditRef);
        zms.deleteSubDomain(mockDomRsrcCtx, "SubDomNoVirtual", "sub2", auditRef);
        zms.deleteSubDomain(mockDomRsrcCtx, "SubDomNoVirtual", "sub1", auditRef);
        zms.deleteTopLevelDomain(mockDomRsrcCtx, "SubDomNoVirtual", auditRef);
        System.clearProperty(ZMSConsts.ZMS_PROP_VIRTUAL_DOMAIN_LIMIT);
    }
    
    @Test
    public void testCreateSubDomainVirtual() {
        
        System.setProperty(ZMSConsts.ZMS_PROP_VIRTUAL_DOMAIN_LIMIT, "5");
        ZMSImpl zmsTest = zmsInit();

        SubDomain dom = createSubDomainObject("user1", "user",
                "Test Domain", "testOrg", adminUser);
        Domain resDom = zmsTest.postSubDomain(mockDomRsrcCtx, "user", auditRef, dom);
        assertNotNull(resDom);

        dom = createSubDomainObject("sub1", "user.user1",
                "Test Domain2", "testOrg", adminUser);
        resDom = zmsTest.postSubDomain(mockDomRsrcCtx, "user.user1", auditRef, dom);
        assertNotNull(resDom);
        
        dom = createSubDomainObject("sub2", "user.user1",
                "Test Domain2", "testOrg", adminUser);
        resDom = zmsTest.postSubDomain(mockDomRsrcCtx, "user.user1", auditRef, dom);
        assertNotNull(resDom);
        
        dom = createSubDomainObject("sub3", "user.user1",
                "Test Domain2", "testOrg", adminUser);
        resDom = zmsTest.postSubDomain(mockDomRsrcCtx, "user.user1", auditRef, dom);
        assertNotNull(resDom);
        
        dom = createSubDomainObject("sub1a", "user.user1.sub1",
                "Test Domain2", "testOrg", adminUser);
        resDom = zmsTest.postSubDomain(mockDomRsrcCtx, "user.user1.sub1", auditRef, dom);
        assertNotNull(resDom);
        
        dom = createSubDomainObject("sub1aa", "user.user1.sub1.sub1a",
                "Test Domain2", "testOrg", adminUser);
        resDom = zmsTest.postSubDomain(mockDomRsrcCtx, "user.user1.sub1.sub1a", auditRef, dom);
        assertNotNull(resDom);
        
        dom = createSubDomainObject("sub1ab", "user.user1.sub1.sub1a",
                "Test Domain2", "testOrg", adminUser);
        try {
            zmsTest.postSubDomain(mockDomRsrcCtx, "user.user1.sub1.sub1a", auditRef, dom);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 403);
        }
        
        dom = createSubDomainObject("sub4", "user.user1",
                "Test Domain2", "testOrg", adminUser);
        try {
            zms.postSubDomain(mockDomRsrcCtx, "user.user1", auditRef, dom);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 403);
        }
        
        zms.deleteSubDomain(mockDomRsrcCtx, "user.user1.sub1.sub1a", "sub1aa", auditRef);
        zms.deleteSubDomain(mockDomRsrcCtx, "user.user1.sub1", "sub1a", auditRef);
        zms.deleteSubDomain(mockDomRsrcCtx, "user.user1", "sub3", auditRef);
        zms.deleteSubDomain(mockDomRsrcCtx, "user.user1", "sub2", auditRef);
        zms.deleteSubDomain(mockDomRsrcCtx, "user.user1", "sub1", auditRef);
        zms.deleteSubDomain(mockDomRsrcCtx, "user", "user1", auditRef);
        System.clearProperty(ZMSConsts.ZMS_PROP_VIRTUAL_DOMAIN_LIMIT);
    }
    
    @Test
    public void testCreateSubDomainVirtualNoLimit() {
        
        System.setProperty(ZMSConsts.ZMS_PROP_VIRTUAL_DOMAIN_LIMIT, "0");
        ZMSImpl zmsTest = zmsInit();

        SubDomain dom = createSubDomainObject("user1", "user",
                "Test Domain", "testOrg", adminUser);
        Domain resDom = zmsTest.postSubDomain(mockDomRsrcCtx, "user", auditRef, dom);
        assertNotNull(resDom);

        dom = createSubDomainObject("sub1", "user.user1",
                "Test Domain2", "testOrg", adminUser);
        resDom = zmsTest.postSubDomain(mockDomRsrcCtx, "user.user1", auditRef, dom);
        assertNotNull(resDom);
        
        dom = createSubDomainObject("sub2", "user.user1",
                "Test Domain2", "testOrg", adminUser);
        resDom = zmsTest.postSubDomain(mockDomRsrcCtx, "user.user1", auditRef, dom);
        assertNotNull(resDom);
        
        dom = createSubDomainObject("sub3", "user.user1",
                "Test Domain2", "testOrg", adminUser);
        resDom = zmsTest.postSubDomain(mockDomRsrcCtx, "user.user1", auditRef, dom);
        assertNotNull(resDom);
        
        dom = createSubDomainObject("sub4", "user.user1",
                "Test Domain2", "testOrg", adminUser);
        resDom = zmsTest.postSubDomain(mockDomRsrcCtx, "user.user1", auditRef, dom);
        assertNotNull(resDom);
        
        dom = createSubDomainObject("sub5", "user.user1",
                "Test Domain2", "testOrg", adminUser);
        resDom = zmsTest.postSubDomain(mockDomRsrcCtx, "user.user1", auditRef, dom);
        assertNotNull(resDom);
        
        dom = createSubDomainObject("sub6", "user.user1",
                "Test Domain2", "testOrg", adminUser);
        resDom = zmsTest.postSubDomain(mockDomRsrcCtx, "user.user1", auditRef, dom);
        assertNotNull(resDom);
        
        zms.deleteSubDomain(mockDomRsrcCtx, "user.user1", "sub6", auditRef);
        zms.deleteSubDomain(mockDomRsrcCtx, "user.user1", "sub5", auditRef);
        zms.deleteSubDomain(mockDomRsrcCtx, "user.user1", "sub4", auditRef);
        zms.deleteSubDomain(mockDomRsrcCtx, "user.user1", "sub3", auditRef);
        zms.deleteSubDomain(mockDomRsrcCtx, "user.user1", "sub2", auditRef);
        zms.deleteSubDomain(mockDomRsrcCtx, "user.user1", "sub1", auditRef);
        zms.deleteSubDomain(mockDomRsrcCtx, "user", "user1", auditRef);
        System.clearProperty(ZMSConsts.ZMS_PROP_VIRTUAL_DOMAIN_LIMIT);
    }
    
    @Test
    public void testCreateSubDomainMismatchParent() {

        TopLevelDomain dom1 = createTopLevelDomainObject("AddSubMismatchParentDom1",
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        SubDomain dom2 = createSubDomainObject("AddSubDom2", "AddSubMismatchParentDom1",
                "Test Domain2", "testOrg", adminUser);
        
        try {
            zms.postSubDomain(mockDomRsrcCtx, "AddSubMismatchParentDom2", auditRef, dom2);
        } catch (ResourceException ex) {
            assertEquals(403, ex.getCode());
        }

        zms.deleteTopLevelDomain(mockDomRsrcCtx, "AddSubMismatchParentDom1", auditRef);
    }

    @Test
    public void testCreateSubdomainOnceOnly() {

        TopLevelDomain dom1 = createTopLevelDomainObject("AddOnceSubDom1",
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        SubDomain dom2 = createSubDomainObject("AddOnceSubDom2",
                "AddOnceSubDom1", "Test Domain2", "testOrg", adminUser);
        Domain resDom1 = zms.postSubDomain(mockDomRsrcCtx, "AddOnceSubDom1", auditRef, dom2);
        assertNotNull(resDom1);

        // we should get an exception for the second call

        try {
            zms.postSubDomain(mockDomRsrcCtx, "AddOnceSubDom1", auditRef, dom2);
            fail();
        } catch (Exception ex) {
            assertTrue(true);
        }

        zms.deleteSubDomain(mockDomRsrcCtx, "AddOnceSubDom1", "AddOnceSubDom2", auditRef);
        zms.deleteTopLevelDomain(mockDomRsrcCtx, "AddOnceSubDom1", auditRef);
    }

    @Test
    public void testDeleteDomain() {

        // make sure we can't delete system domains

        try {
            zms.deleteDomain(mockDomRsrcCtx, auditRef, "sys.auth", "testDeleteDomain");
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 400);
            assertTrue(ex.getMessage().contains("reserved system domain"));
        }

        TopLevelDomain dom = createTopLevelDomainObject(
            "TestDeleteDomain", null, null, adminUser);
        dom.setAuditEnabled(true);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom);

        zms.deleteDomain(mockDomRsrcCtx, auditRef, "testdeletedomain", "testDeleteDomain");

        try {
            zms.getDomain(mockDomRsrcCtx, "TestDeleteDomain");
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 404);
        }
    }

    @Test
    public void testDeleteDomainNonExistant() {
        try {
            zms.deleteDomain(mockDomRsrcCtx, auditRef, "TestDeleteDomainNonExist", "testDeleteDomainNonExistant");
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 404);
        }
    }

    @Test
    public void testDeleteDomainMissingAuditRef() {
        // create domain and require auditing
        String domain = "testdeletedomainmissingauditref";
        TopLevelDomain dom = createTopLevelDomainObject(
            domain, null, null, adminUser);
        dom.setAuditEnabled(true);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom);

        // delete it without an auditRef and catch exception
        try {
            zms.deleteDomain(mockDomRsrcCtx, null, domain, "testDeleteDomainMissingAuditRef");
            fail("requesterror not thrown by testDeleteDomainMissingAuditRef.");
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 400);
            assertTrue(ex.getMessage().contains("Audit reference required"));
        } finally {
            zms.deleteTopLevelDomain(mockDomRsrcCtx, domain, auditRef);
        }
    }

    @Test
    public void testDeleteTopLevelDomain() {

        TopLevelDomain dom1 = createTopLevelDomainObject("DelTopDom1",
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        Domain resDom1 = zms.getDomain(mockDomRsrcCtx, "DelTopDom1");
        assertNotNull(resDom1);

        zms.deleteTopLevelDomain(mockDomRsrcCtx, "DelTopDom1", auditRef);

        // we should get a forbidden exception since the domain
        // no longer exists

        try {
            zms.getDomain(mockDomRsrcCtx, "DelTopDom1");
            fail();
        } catch (Exception ex) {
            assertTrue(true);
        }
    }
    
    @Test
    public void testDeleteTopLevelDomainChildExist() {

        TestAuditLogger alogger = new TestAuditLogger();
        ZMSImpl zmsImpl = getZmsImpl(alogger);

        TopLevelDomain dom1 = createTopLevelDomainObject("DelTopChildDom1",
                "Test Domain1", "testOrg", adminUser);
        zmsImpl.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        SubDomain dom2 = createSubDomainObject("DelSubDom2", "DelTopChildDom1",
                "Test Domain2", "testOrg", adminUser);
        zmsImpl.postSubDomain(mockDomRsrcCtx, "DelTopChildDom1", auditRef, dom2);

        // we can't delete Dom1 since Dom2 still exists
        
        try {
            zmsImpl.deleteTopLevelDomain(mockDomRsrcCtx, "DelTopChildDom1", auditRef);
            fail("requesterror not thrown.");
        } catch (ResourceException ex) {
            assertEquals(400, ex.getCode());
        }
        
        zmsImpl.deleteSubDomain(mockDomRsrcCtx, "DelTopChildDom1", "DelSubDom2", auditRef);
        zmsImpl.deleteTopLevelDomain(mockDomRsrcCtx, "DelTopChildDom1", auditRef);
    }

    @Test
    public void testDeleteTopLevelDomainNonExistant() {
        try {
            zms.deleteTopLevelDomain(mockDomRsrcCtx, "NonExistantDomain", auditRef);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 404);
        }
    }

    @Test
    public void testDeleteTopLevelDomainNonExistantNoAuditRef() {
        try {
            zms.deleteTopLevelDomain(mockDomRsrcCtx, "NonExistantDomain", null);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 404);
        }
    }

    @Test
    public void testDeleteTopLevelDomainMissingAuditRef() {
        // create domain and require auditing
        TopLevelDomain dom = createTopLevelDomainObject(
            "TopDomainAuditRequired", null, null, adminUser);
        dom.setAuditEnabled(true);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom);

        // delete it without an auditRef and catch exception
        try {
            zms.deleteTopLevelDomain(mockDomRsrcCtx, "TopDomainAuditRequired", null);
            fail("requesterror not thrown by deleteTopLevelDomain.");
        } catch (ResourceException ex) {
            assertEquals(400, ex.getCode());
            assertTrue(ex.getMessage().contains("Audit reference required"));
        } finally {
            zms.deleteTopLevelDomain(mockDomRsrcCtx, "TopDomainAuditRequired", auditRef);
        }
    }

    @Test
    public void testDeleteSubDomain() {

        TopLevelDomain dom1 = createTopLevelDomainObject("DelSubDom1",
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        SubDomain dom2 = createSubDomainObject("DelSubDom2", "DelSubDom1",
                "Test Domain2", "testOrg", adminUser);
        zms.postSubDomain(mockDomRsrcCtx, "DelSubDom1", auditRef, dom2);

        Domain resDom1 = zms.getDomain(mockDomRsrcCtx, "DelSubDom1.DelSubDom2");
        assertNotNull(resDom1);

        zms.deleteSubDomain(mockDomRsrcCtx, "DelSubDom1", "DelSubDom2", auditRef);

        // we should get a forbidden exception since the domain
        // no longer exists

        try {
            zms.getDomain(mockDomRsrcCtx, "DelSubDom1.DelSubDom2");
            fail();
        } catch (Exception ex) {
            assertTrue(true);
        }

        zms.deleteTopLevelDomain(mockDomRsrcCtx, "DelSubDom1", auditRef);
    }

    @Test
    public void testDeleteSubDomainChildExist() {

        TestAuditLogger alogger = new TestAuditLogger();
        ZMSImpl zmsImpl = getZmsImpl(alogger);

        TopLevelDomain dom1 = createTopLevelDomainObject("DelSubChildDom1",
                "Test Domain1", "testOrg", adminUser);
        zmsImpl.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        SubDomain dom2 = createSubDomainObject("DelSubDom2", "DelSubChildDom1",
                "Test Domain2", "testOrg", adminUser);
        zmsImpl.postSubDomain(mockDomRsrcCtx, "DelSubChildDom1", auditRef, dom2);

        SubDomain dom3 = createSubDomainObject("DelSubDom3", "DelSubChildDom1.DelSubDom2",
                "Test Domain3", "testOrg", adminUser);
        zmsImpl.postSubDomain(mockDomRsrcCtx, "DelSubChildDom1.DelSubDom2", auditRef, dom3);

        // we can't delete Dom2 since Dom3 still exists
        
        try {
            zmsImpl.deleteSubDomain(mockDomRsrcCtx, "DelSubChildDom1", "DelSubDom2", auditRef);
        } catch (ResourceException ex) {
            assertEquals(400, ex.getCode());
        }

        zmsImpl.deleteSubDomain(mockDomRsrcCtx, "DelSubChildDom1.DelSubDom2", "DelSubDom3", auditRef);
        zmsImpl.deleteSubDomain(mockDomRsrcCtx, "DelSubChildDom1", "DelSubDom2", auditRef);
        zmsImpl.deleteTopLevelDomain(mockDomRsrcCtx, "DelSubChildDom1", auditRef);
    }
    
    @Test
    public void testDeleteSubDomainNonExistant() {
        TopLevelDomain dom = createTopLevelDomainObject(
            "ExistantTopDomain", null, null, adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom);
        try {
            zms.deleteSubDomain(mockDomRsrcCtx, "ExistantTopDomain", "NonExistantSubDomain", auditRef);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 404);
        }
        zms.deleteTopLevelDomain(mockDomRsrcCtx, "ExistantTopDomain", auditRef);
    }

    @Test
    public void testDeleteSubDomainSubAndTopNonExistant() {
        try {
            zms.deleteSubDomain(mockDomRsrcCtx, "NonExistantTopDomain", "NonExistantSubDomain", auditRef);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 404);
        }
    }

    @Test
    public void testDeleteSubDomainMissingAuditRef() {
        TopLevelDomain dom = createTopLevelDomainObject(
            "ExistantTopDomain2", null, null, adminUser);
        dom.setAuditEnabled(true);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom);

        SubDomain subDom = createSubDomainObject(
            "ExistantSubDom2", "ExistantTopDomain2",
            null, null, adminUser);
        subDom.setAuditEnabled(true);
        zms.postSubDomain(mockDomRsrcCtx, "ExistantTopDomain2", auditRef, subDom);

        try {
            zms.deleteSubDomain(mockDomRsrcCtx, "ExistantTopDomain2", "ExistantSubDom2", null);
            fail("requesterror not thrown by deleteSubDomain.");
        } catch (ResourceException ex) {
            assertEquals(400, ex.getCode());
            assertTrue(ex.getMessage().contains("Audit reference required"));
        } finally {
            zms.deleteSubDomain(mockDomRsrcCtx, "ExistantTopDomain2", "ExistantSubDom2", auditRef);
            zms.deleteTopLevelDomain(mockDomRsrcCtx, "ExistantTopDomain2", auditRef);
        }
    }

    @Test
    public void testPutDomainMetaThrowException() {

        TestAuditLogger alogger = new TestAuditLogger();
        ZMSImpl zmsImpl = getZmsImpl(alogger);

        String domName = "wrongDomainName";
        DomainMeta meta = new DomainMeta();
        meta.setYpmId(getRandomProductId());
        try {
            zmsImpl.putDomainMeta(mockDomRsrcCtx, domName, auditRef, meta);
            fail("notfounderror not thrown.");
        } catch (ResourceException e) {
            assertEquals(404, e.getCode());
        }
    }

    @Test
    public void testPutDomainMeta() {

        TopLevelDomain dom1 = createTopLevelDomainObject("MetaDom1",
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        Domain resDom1 = zms.getDomain(mockDomRsrcCtx, "MetaDom1");
        assertNotNull(resDom1);
        assertEquals(resDom1.getDescription(), "Test Domain1");
        assertEquals(resDom1.getOrg(), "testorg");
        assertTrue(resDom1.getEnabled());
        assertFalse(resDom1.getAuditEnabled());
        assertNull(resDom1.getServiceCertExpiryMins());
        assertNull(resDom1.getRoleCertExpiryMins());
        assertNull(resDom1.getMemberExpiryDays());
        assertNull(resDom1.getServiceExpiryDays());
        assertNull(resDom1.getTokenExpiryMins());

        DomainMeta meta = createDomainMetaObject("Test2 Domain", "NewOrg",
                true, true, "12345", 1001);
        meta.setCertDnsDomain("YAHOO.cloud");
        meta.setServiceCertExpiryMins(100);
        meta.setRoleCertExpiryMins(200);
        meta.setSignAlgorithm("ec");
        zms.putDomainMeta(mockDomRsrcCtx, "MetaDom1", auditRef, meta);
        zms.putDomainSystemMeta(mockDomRsrcCtx, "MetaDom1", "auditenabled", auditRef, meta);
        zms.putDomainSystemMeta(mockDomRsrcCtx, "MetaDom1", "account", auditRef, meta);
        zms.putDomainSystemMeta(mockDomRsrcCtx, "MetaDom1", "certdnsdomain", auditRef, meta);

        setupPrincipalSystemMetaDelete(zms, mockDomRsrcCtx.principal().getFullName(), "metadom1", "productid", "org", "certdnsdomain");
        zms.putDomainSystemMeta(mockDomRsrcCtx, "MetaDom1", "org", auditRef, meta);
        zms.putDomainSystemMeta(mockDomRsrcCtx, "MetaDom1", "productid", auditRef, meta);

        Domain resDom3 = zms.getDomain(mockDomRsrcCtx, "MetaDom1");
        assertNotNull(resDom3);
        assertEquals(resDom3.getDescription(), "Test2 Domain");
        assertEquals(resDom3.getOrg(), "neworg");
        assertTrue(resDom3.getEnabled());
        assertTrue(resDom3.getAuditEnabled());
        assertEquals(resDom3.getAccount(), "12345");
        assertEquals(Integer.valueOf(1001), resDom3.getYpmId());
        assertEquals(resDom3.getCertDnsDomain(), "yahoo.cloud");
        assertEquals(resDom3.getServiceCertExpiryMins(), Integer.valueOf(100));
        assertEquals(resDom3.getRoleCertExpiryMins(), Integer.valueOf(200));
        assertNull(resDom3.getMemberExpiryDays());
        assertNull(resDom3.getServiceExpiryDays());
        assertNull(resDom3.getTokenExpiryMins());
        assertEquals(resDom3.getSignAlgorithm(), "ec");

        // put the meta data using same product id

        meta = createDomainMetaObject("just a new desc", "organs",
                true, true, "12345", 1001);
        meta.setMemberExpiryDays(300);
        meta.setServiceExpiryDays(350);
        meta.setTokenExpiryMins(400);
        zms.putDomainMeta(mockDomRsrcCtx, "MetaDom1", auditRef, meta);

        resDom3 = zms.getDomain(mockDomRsrcCtx, "MetaDom1");
        assertNotNull(resDom3);
        assertEquals(resDom3.getDescription(), "just a new desc");
        //org is system attr. so it wont be changed by putdomainmeta call
        assertEquals(resDom3.getOrg(), "neworg");
        assertTrue(resDom3.getEnabled());
        assertTrue(resDom3.getAuditEnabled());
        assertEquals(resDom3.getAccount(), "12345");
        assertEquals(Integer.valueOf(1001), resDom3.getYpmId());
        assertEquals(resDom3.getServiceCertExpiryMins(), Integer.valueOf(100));
        assertEquals(resDom3.getRoleCertExpiryMins(), Integer.valueOf(200));
        assertEquals(resDom3.getMemberExpiryDays(), Integer.valueOf(300));
        assertEquals(resDom3.getServiceExpiryDays(), Integer.valueOf(350));
        assertEquals(resDom3.getTokenExpiryMins(), Integer.valueOf(400));

        zms.putDomainSystemMeta(mockDomRsrcCtx, "MetaDom1", "org", auditRef, meta);
        resDom3 = zms.getDomain(mockDomRsrcCtx, "MetaDom1");
        assertNotNull(resDom3);
        assertEquals(resDom3.getOrg(), "organs");

        // put the meta data using new product
        meta = createDomainMetaObject("just a new desc", "organs",
                true, true, "12345", 1001);
        Integer newProductId = getRandomProductId();
        meta.setYpmId(newProductId);
        meta.setServiceCertExpiryMins(5);
        meta.setRoleCertExpiryMins(0);
        meta.setMemberExpiryDays(15);
        meta.setServiceExpiryDays(17);
        meta.setTokenExpiryMins(20);
        meta.setSignAlgorithm("rsa");
        zms.putDomainMeta(mockDomRsrcCtx, "MetaDom1", auditRef, meta);
        zms.putDomainSystemMeta(mockDomRsrcCtx, "MetaDom1", "productid", auditRef, meta);

        resDom3 = zms.getDomain(mockDomRsrcCtx, "MetaDom1");
        assertNotNull(resDom3);
        assertEquals(resDom3.getDescription(), "just a new desc");
        assertEquals(resDom3.getOrg(), "organs");
        assertTrue(resDom3.getEnabled());
        assertTrue(resDom3.getAuditEnabled());
        assertEquals(resDom3.getAccount(), "12345");
        assertEquals(newProductId, resDom3.getYpmId());
        assertEquals(resDom3.getServiceCertExpiryMins(), Integer.valueOf(5));
        assertNull(resDom3.getRoleCertExpiryMins());
        assertEquals(resDom3.getMemberExpiryDays(), Integer.valueOf(15));
        assertEquals(resDom3.getServiceExpiryDays(), Integer.valueOf(17));
        assertEquals(resDom3.getTokenExpiryMins(), Integer.valueOf(20));
        assertEquals(resDom3.getSignAlgorithm(), "rsa");

        cleanupPrincipalSystemMetaDelete(zms);
        zms.deleteTopLevelDomain(mockDomRsrcCtx, "MetaDom1", auditRef);
    }

    @Test
    public void testPutDomainSystemMetaModifiedTimestamp() throws InterruptedException {

        final String domainName = "metadomainmodified";
        TopLevelDomain dom1 = createTopLevelDomainObject(domainName,
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        Domain resDom1 = zms.getDomain(mockDomRsrcCtx, domainName);
        assertNotNull(resDom1);
        long domMod1 = resDom1.getModified().millis();

        Thread.sleep(1);

        DomainMeta meta = new DomainMeta();
        zms.putDomainSystemMeta(mockDomRsrcCtx, domainName, "modified", auditRef, meta);

        Domain resDom2 = zms.getDomain(mockDomRsrcCtx, domainName);
        assertNotNull(resDom2);
        long domMod2 = resDom2.getModified().millis();

        assertTrue(domMod2 > domMod1);
        zms.deleteTopLevelDomain(mockDomRsrcCtx, domainName, auditRef);
    }

    @Test
    public void testPutDomainMetaInvalid() {

        // enable product id support
        
        System.setProperty(ZMSConsts.ZMS_PROP_PRODUCT_ID_SUPPORT, "true");
        ZMSImpl zmsImpl = zmsInit();

        final String domainName = "MetaDomProductid";
        TopLevelDomain dom = createTopLevelDomainObject(domainName,
                "Test Domain", "testOrg", adminUser);
        zmsImpl.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom);

        Domain resDom = zmsImpl.getDomain(mockDomRsrcCtx, domainName);
        assertNotNull(resDom);
        assertEquals(resDom.getDescription(), "Test Domain");
        assertEquals(resDom.getOrg(), "testorg");
        assertTrue(resDom.getEnabled());
        assertFalse(resDom.getAuditEnabled());
        Integer productId = resDom.getYpmId();

        setupPrincipalSystemMetaDelete(zms, mockDomRsrcCtx.principal().getFullName(), domainName, "productid");
        DomainMeta meta = createDomainMetaObject("Test2 Domain", "NewOrg",
                true, true, "12345", null);
        try {
            zmsImpl.putDomainSystemMeta(mockDomRsrcCtx, domainName, "productid", auditRef, meta);
            fail("bad request exc not thrown");
        } catch (ResourceException exc) {
            assertEquals(400, exc.getCode());
            assertTrue(exc.getMessage().contains("Unique Product Id must be specified for top level domain"));
        }

        // put meta data using another domains productId
        dom = createTopLevelDomainObject("MetaDomProductid2",
                "Test Domain", "testOrg", adminUser);
        zmsImpl.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom);

        resDom = zmsImpl.getDomain(mockDomRsrcCtx, "MetaDomProductid2");
        Integer productId2 = resDom.getYpmId();
        assertNotEquals(productId, productId2);

        meta = createDomainMetaObject("Test3 Domain", "NewOrg",
                true, true, "12345", productId2);
        try {
            zmsImpl.putDomainSystemMeta(mockDomRsrcCtx, domainName, "productid", auditRef, meta);
            fail("bad request exc not thrown");
        } catch (ResourceException exc) {
            assertEquals(400, exc.getCode());
            assertTrue(exc.getMessage().contains("is already assigned to domain"));
        }

        // test negative values

        meta = new DomainMeta().setServiceExpiryDays(-10);
        try {
            zmsImpl.putDomainMeta(mockDomRsrcCtx, domainName, auditRef, meta);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), ResourceException.BAD_REQUEST);
        }

        meta = new DomainMeta().setMemberExpiryDays(-10);
        try {
            zmsImpl.putDomainMeta(mockDomRsrcCtx, domainName, auditRef, meta);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), ResourceException.BAD_REQUEST);
        }

        meta = new DomainMeta().setRoleCertExpiryMins(-10);
        try {
            zmsImpl.putDomainMeta(mockDomRsrcCtx, domainName, auditRef, meta);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), ResourceException.BAD_REQUEST);
        }

        meta = new DomainMeta().setServiceCertExpiryMins(-10);
        try {
            zmsImpl.putDomainMeta(mockDomRsrcCtx, domainName, auditRef, meta);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), ResourceException.BAD_REQUEST);
        }

        meta = new DomainMeta().setTokenExpiryMins(-10);
        try {
            zmsImpl.putDomainMeta(mockDomRsrcCtx, domainName, auditRef, meta);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), ResourceException.BAD_REQUEST);
        }

        cleanupPrincipalSystemMetaDelete(zms);
        zmsImpl.deleteTopLevelDomain(mockDomRsrcCtx, "MetaDomProductid", auditRef);
        zmsImpl.deleteTopLevelDomain(mockDomRsrcCtx, "MetaDomProductid2", auditRef);
        System.clearProperty(ZMSConsts.ZMS_PROP_PRODUCT_ID_SUPPORT);
        zmsImpl.objectStore.clearConnections();
    }

    @Test
    public void testPutDomainMetaDefaults() {

        TopLevelDomain dom1 = createTopLevelDomainObject("MetaDom2",
                null, null, adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        Domain resDom1 = zms.getDomain(mockDomRsrcCtx, "MetaDom2");
        assertNotNull(resDom1);
        assertNull(resDom1.getDescription());
        assertNull(resDom1.getOrg());
        assertTrue(resDom1.getEnabled());
        assertFalse(resDom1.getAuditEnabled());

        DomainMeta meta = createDomainMetaObject("Test2 Domain", "NewOrg",
                true, false, null, 0);
        zms.putDomainMeta(mockDomRsrcCtx, "MetaDom2", auditRef, meta);

        zms.putDomainSystemMeta(mockDomRsrcCtx, "MetaDom2", "org", auditRef, meta);

        Domain resDom3 = zms.getDomain(mockDomRsrcCtx, "MetaDom2");
        assertNotNull(resDom3);
        assertEquals(resDom3.getDescription(), "Test2 Domain");
        assertEquals(resDom3.getOrg(), "neworg");
        assertTrue(resDom3.getEnabled());
        assertFalse(resDom3.getAuditEnabled());
        assertNull(resDom3.getAccount());
        assertEquals(Integer.valueOf(0), resDom3.getYpmId());

        zms.deleteTopLevelDomain(mockDomRsrcCtx, "MetaDom2", auditRef);
    }

    @Test
    public void testPutDomainMetaMissingAuditRef() {
        String domain = "testSetDomainMetaMissingAuditRef";
        TopLevelDomain dom = createTopLevelDomainObject(
                domain, "Test1 Domain", "testOrg", adminUser);
        dom.setAuditEnabled(true);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom);

        Domain resDom = zms.getDomain(mockDomRsrcCtx, domain);
        assertNotNull(resDom);
        assertEquals(resDom.getDescription(), "Test1 Domain");
        assertEquals(resDom.getOrg(), "testorg");
        assertTrue(resDom.getAuditEnabled());

        DomainMeta meta = createDomainMetaObject("Test2 Domain", "NewOrg", false, true, null, 0);
        try {
            zms.putDomainMeta(mockDomRsrcCtx, domain, null, meta);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 400);
            assertTrue(ex.getMessage().contains("Audit reference required"));
        } finally {
            zms.deleteTopLevelDomain(mockDomRsrcCtx, domain, auditRef);
        }
    }

    @Test
    public void testPutDomainMetaSubDomain() {
        try {
            TopLevelDomain dom = createTopLevelDomainObject("MetaDomProductid",
                "Test Domain", "testOrg", adminUser);
            zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom);
        } catch (ResourceException rexc) {
            assertEquals(400, rexc.getCode());
        }

        SubDomain subDom = createSubDomainObject("metaSubDom", "MetaDomProductid",
                "sub Domain", "testOrg", adminUser);
        zms.postSubDomain(mockDomRsrcCtx, "MetaDomProductid", auditRef, subDom);

        // put meta data with null productId
        DomainMeta meta = createDomainMetaObject("Test sub Domain", "NewOrg",
                true, true, "12345", null);
        zms.putDomainMeta(mockDomRsrcCtx, "MetaDomProductid.metaSubDom", auditRef, meta);

        // put meta data with a productId
        meta = createDomainMetaObject("Test sub Domain", "NewOrg",
                true, true, "12345", getRandomProductId());
        zms.putDomainMeta(mockDomRsrcCtx, "MetaDomProductid.metaSubDom", auditRef, meta);

        // set the expiry days to 30

        meta.setMemberExpiryDays(30);
        meta.setServiceExpiryDays(25);
        zms.putDomainMeta(mockDomRsrcCtx, "MetaDomProductid.metaSubDom", auditRef, meta);
        Domain domain = zms.getDomain(mockDomRsrcCtx, "MetaDomProductid.metaSubDom");
        assertEquals(domain.getMemberExpiryDays(), Integer.valueOf(30));
        assertEquals(domain.getServiceExpiryDays(), Integer.valueOf(25));

        // if value is null we're not going to change it

        meta.setMemberExpiryDays(null);
        meta.setServiceExpiryDays(null);
        meta.setDescription("test1");
        zms.putDomainMeta(mockDomRsrcCtx, "MetaDomProductid.metaSubDom", auditRef, meta);
        domain = zms.getDomain(mockDomRsrcCtx, "MetaDomProductid.metaSubDom");
        assertEquals(domain.getMemberExpiryDays(), Integer.valueOf(30));
        assertEquals(domain.getServiceExpiryDays(), Integer.valueOf(25));
        assertEquals(domain.getDescription(), "test1");

        // setting is to 0

        meta.setMemberExpiryDays(0);
        meta.setServiceExpiryDays(0);
        meta.setDescription("test2");
        zms.putDomainMeta(mockDomRsrcCtx, "MetaDomProductid.metaSubDom", auditRef, meta);
        domain = zms.getDomain(mockDomRsrcCtx, "MetaDomProductid.metaSubDom");
        assertNull(domain.getMemberExpiryDays());
        assertNull(domain.getServiceExpiryDays());
        assertEquals(domain.getDescription(), "test2");

        zms.deleteSubDomain(mockDomRsrcCtx, "MetaDomProductid", "metaSubDom", auditRef);
        zms.deleteTopLevelDomain(mockDomRsrcCtx, "MetaDomProductid", auditRef);
    }

    @Test
    public void testGetRoleList() {

        TopLevelDomain dom1 = createTopLevelDomainObject("RoleListDom1",
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        Role role1 = createRoleObject("RoleListDom1", "Role1", null, "user.joe",
                "user.jane");
        zms.putRole(mockDomRsrcCtx, "RoleListDom1", "Role1", auditRef, role1);

        Role role2 = createRoleObject("RoleListDom1", "Role2", null, "user.joe",
                "user.jane");
        zms.putRole(mockDomRsrcCtx, "RoleListDom1", "Role2", auditRef, role2);

        RoleList roleList = zms.getRoleList(mockDomRsrcCtx, "RoleListDom1", null, null);
        assertNotNull(roleList);

        assertTrue(roleList.getNames().contains("Role1".toLowerCase()));
        assertTrue(roleList.getNames().contains("Role2".toLowerCase()));

        zms.deleteTopLevelDomain(mockDomRsrcCtx, "RoleListDom1", auditRef);
    }
    
    @Test
    public void testGetRoleListParams() {

        TopLevelDomain dom1 = createTopLevelDomainObject("RoleListParamDom1",
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        Role role1 = createRoleObject("RoleListParamDom1", "Role1", null,
                "user.joe", "user.jane");
        zms.putRole(mockDomRsrcCtx, "RoleListParamDom1", "Role1", auditRef, role1);

        Role role2 = createRoleObject("RoleListParamDom1", "Role2", null,
                "user.joe", "user.jane");
        zms.putRole(mockDomRsrcCtx, "RoleListParamDom1", "Role2", auditRef, role2);

        RoleList roleList = zms.getRoleList(mockDomRsrcCtx, "RoleListParamDom1", null, "Role1");
        assertNotNull(roleList);

        assertFalse(roleList.getNames().contains("Role1".toLowerCase()));
        assertTrue(roleList.getNames().contains("Role2".toLowerCase()));

        zms.deleteTopLevelDomain(mockDomRsrcCtx, "RoleListParamDom1", auditRef);
    }
    
    @Test
    public void testGetRoleListThrowException() {
        try {
            zms.getRoleList(mockDomRsrcCtx, "wrongDomainName", null, null);
            fail("notfounderror not thrown.");
        } catch (ResourceException e) {
            assertEquals(e.getCode(), 404);
        }
    }

    @Test
    public void testGetRole() {

        TopLevelDomain dom1 = createTopLevelDomainObject("GetRoleDom1",
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        Role role1 = createRoleObject("GetRoleDom1", "Role1", null,
                "user.joe", "user.jane");
        zms.putRole(mockDomRsrcCtx, "GetRoleDom1", "Role1", auditRef, role1);

        Role role = zms.getRole(mockDomRsrcCtx, "GetRoleDom1", "Role1", false, false, false);
        assertNotNull(role);

        assertEquals(role.getName(), "GetRoleDom1:role.Role1".toLowerCase());
        assertNull(role.getTrust());
        List<RoleMember> members = role.getRoleMembers();
        assertNotNull(members);
        assertEquals(members.size(), 2);

        List<String> checkList = new ArrayList<>();
        checkList.add("user.joe");
        checkList.add("user.jane");
        checkRoleMember(checkList, members);
        
        zms.deleteTopLevelDomain(mockDomRsrcCtx, "GetRoleDom1", auditRef);
    }

    @Test
    public void testGetRoleWithAttributes() {

        TopLevelDomain dom1 = createTopLevelDomainObject("GetRoleDom1",
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        Role role1 = createRoleObject("GetRoleDom1", "Role1", null,
                "user.joe", "user.jane");
        role1.setMemberExpiryDays(30);
        role1.setServiceExpiryDays(35);
        role1.setSelfServe(true);
        role1.setMemberReviewDays(70);
        role1.setServiceReviewDays(80);
        zms.putRole(mockDomRsrcCtx, "GetRoleDom1", "Role1", auditRef, role1);

        Role role = zms.getRole(mockDomRsrcCtx, "GetRoleDom1", "Role1", false, false, false);
        assertNotNull(role);

        assertEquals(role.getName(), "GetRoleDom1:role.Role1".toLowerCase());
        assertNull(role.getTrust());
        List<RoleMember> members = role.getRoleMembers();
        assertNotNull(members);
        assertEquals(members.size(), 2);

        List<String> checkList = new ArrayList<>();
        checkList.add("user.joe");
        checkList.add("user.jane");
        checkRoleMember(checkList, members);

        assertEquals(role.getMemberExpiryDays(), Integer.valueOf(30));
        assertEquals(role.getServiceExpiryDays(), Integer.valueOf(35));
        assertEquals(role.getMemberReviewDays(), Integer.valueOf(70));
        assertEquals(role.getServiceReviewDays(), Integer.valueOf(80));
        assertTrue(role.getSelfServe());

        zms.deleteTopLevelDomain(mockDomRsrcCtx, "GetRoleDom1", auditRef);
    }

    @Test
    public void testGetRoleThrowException() {
        String domainName = "MbrGetRoleDom1";
        String roleName = "Role1";
        
        // Tests the getRole() condition: if (domain == null)...
        try {
            zms.getRole(mockDomRsrcCtx, domainName, roleName, false, false, false);
            fail("notfounderror not thrown.");
        } catch (ResourceException e) {
            assertEquals(e.getCode(), 404);
        }
        
        TopLevelDomain dom1 = createTopLevelDomainObject(domainName,
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);
        
        // Tests the getRole() condition: if (collection == null)...
        try {
            // Should fail because we did not create a role resource.
            zms.getRole(mockDomRsrcCtx, domainName, roleName, false, false, false);
            fail("notfounderror not thrown.");
        } catch (ResourceException e) {
            assertEquals(e.getCode(), 404);
        }
        
        // Tests the getRole() condition: if (role == null)...
        String wrongRoleName = "Role2";
        try {
            Role role1 = createRoleObject(domainName, roleName, null);
            zms.putRole(mockDomRsrcCtx, domainName, roleName, auditRef, role1);
            
            // Should fail because we are trying to find a non-existent role.
            zms.getRole(mockDomRsrcCtx, domainName, wrongRoleName, false, false, false);
            fail("notfounderror not thrown.");
        } catch (ResourceException e) {
            assertEquals(e.getCode(), 404);
        }
        
        zms.deleteTopLevelDomain(mockDomRsrcCtx, domainName, auditRef);
    }
    
    @Test
    public void testPutRoleThrowException() {

        TestAuditLogger alogger = new TestAuditLogger();
        ZMSImpl zmsImpl = getZmsImpl(alogger);

        String domainName = "DomainName1";
        String roleName = "RoleName1";
        Role role = new Role();
        
        // Tests the getRole() condition : if (!roleResourceName(domainName, roleName).equals(role.getName()))...
        try {
            String roleRoleName = "inconsistentRoleName1";
            role.setName(roleRoleName);
            
            // The role naming is inconsistent.
            zmsImpl.putRole(mockDomRsrcCtx, domainName, roleName, auditRef, role);
            fail("requesterror not thrown.");
        } catch (ResourceException e) {
            assertEquals(e.getCode(), 400);
        }
        
        // Tests the getRole() condition : if (domain == null)...
        try {
            String roleRoleName = "DomainName1:role.RoleName1";
            role.setName(roleRoleName);
            
            // We never created a domain for this role.
            zmsImpl.putRole(mockDomRsrcCtx, domainName, roleName, auditRef, role);
            fail("notfounderror not thrown.");
        } catch (ResourceException e) {
            assertEquals(e.getCode(), 404);
        }
    }

    @Test
    public void testCreateRole() {

        TestAuditLogger alogger = new TestAuditLogger();
        List<String> aLogMsgs = alogger.getLogMsgList();
        ZMSImpl zmsImpl = getZmsImpl(alogger);

        TopLevelDomain dom1 = createTopLevelDomainObject("CreateRoleDom1",
                "Test Domain1", "testOrg", adminUser);
        Mockito.when(mockDomRsrcCtx.getApiName()).thenReturn("posttopleveldomain").thenReturn("putrole");
        zmsImpl.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        Role role1 = createRoleObject("CreateRoleDom1", "Role1", null,
                "user.joe", "user.jane");
        zmsImpl.putRole(mockDomRsrcCtx, "CreateRoleDom1", "Role1", auditRef, role1);

        Role role3 = zmsImpl.getRole(mockDomRsrcCtx, "CreateRoleDom1", "Role1", false, false, false);
        assertNotNull(role3);
        assertEquals(role3.getName(), "CreateRoleDom1:role.Role1".toLowerCase());
        assertNull(role3.getTrust());

        // check audit log msg for putRole
        boolean foundError = false;
        System.err.println("testCreateRole: Number of lines: " + aLogMsgs.size());
        for (String msg: aLogMsgs) {
            if (!msg.contains("WHAT-api=(putrole)")) {
                continue;
            }
            assertTrue(msg.contains("CLIENT-IP=(" + MOCKCLIENTADDR + ")"), msg);
            int index = msg.indexOf("WHAT-details=(");
            assertTrue(index != -1, msg);
            int index2 = msg.indexOf("\"name\": \"role1\", \"trust\": \"null\", \"added-members\": [");
            assertTrue(index2 > index, msg);
            foundError = true;
            break;
        }
        assertTrue(foundError);

        // delete member of the role
        //
        List<RoleMember> listrm = role1.getRoleMembers();
        for (RoleMember rmemb: listrm) {
            if (rmemb.getMemberName().equals("user.jane")) {
                listrm.remove(rmemb);
                break;
            }
        }

        aLogMsgs.clear();
        zmsImpl.putRole(mockDomRsrcCtx, "CreateRoleDom1", "Role1", auditRef, role1);

        foundError = false;
        System.err.println("testCreateRole: Now Number of lines: " + aLogMsgs.size());
        for (String msg: aLogMsgs) {
            if (!msg.contains("WHAT-api=(putrole)")) {
                continue;
            }
            assertTrue(msg.contains("CLIENT-IP=(" + MOCKCLIENTADDR + ")"), msg);
            int index = msg.indexOf("WHAT-details=(");
            assertTrue(index != -1, msg);
            int index2 = msg.indexOf("\"name\": \"role1\", \"trust\": \"null\", \"deleted-members\": [{\"member\": \"user.jane\", \"approved\": true, \"system-disabled\": 0}], \"added-members\": []");
            assertTrue(index2 > index, msg);
            foundError = true;
            break;
        }
        assertTrue(foundError);

        // create a role with no members

        Role role4 = createRoleObject("CreateRoleDom1", "Role4", null);
        zmsImpl.putRole(mockDomRsrcCtx, "CreateRoleDom1", "Role4", auditRef, role4);

        Role role4Res = zmsImpl.getRole(mockDomRsrcCtx, "CreateRoleDom1", "Role4", false, false, false);
        assertNotNull(role4Res);
        assertTrue(role4Res.getRoleMembers().isEmpty());

        zmsImpl.deleteTopLevelDomain(mockDomRsrcCtx, "CreateRoleDom1", auditRef);
    }

    @Test
    public void testCreateRoleLocalNameOnly() {

        TopLevelDomain dom1 = createTopLevelDomainObject("CreateRoleLocalNameOnly",
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        Role role1 = new Role();
        role1.setName("role1");
        role1.setMemberExpiryDays(30);
        role1.setServiceExpiryDays(45);
        role1.setMemberReviewDays(70);
        role1.setServiceReviewDays(80);
        
        zms.putRole(mockDomRsrcCtx, "CreateRoleLocalNameOnly", "Role1", auditRef, role1);

        Role role3 = zms.getRole(mockDomRsrcCtx, "CreateRoleLocalNameOnly", "Role1", false, false, false);
        assertNotNull(role3);
        assertEquals(role3.getName(), "CreateRoleLocalNameOnly:role.Role1".toLowerCase());
        assertEquals(role3.getMemberExpiryDays(), Integer.valueOf(30));
        assertEquals(role3.getServiceExpiryDays(), Integer.valueOf(45));
        assertEquals(role3.getMemberReviewDays(), Integer.valueOf(70));
        assertEquals(role3.getServiceReviewDays(), Integer.valueOf(80));


        zms.deleteTopLevelDomain(mockDomRsrcCtx, "CreateRoleLocalNameOnly", auditRef);
    }
    
    @Test
    public void testCreateRoleMissingAuditRef() {
        String domain = "testCreateRoleMissingAuditRef";
        TopLevelDomain dom = createTopLevelDomainObject(
                domain, "Test Domain1", "testOrg", adminUser);
        dom.setAuditEnabled(true);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom);

        Role role = createRoleObject(
            domain, "Role1", null, "user.joe", "user.jane");
        try {
            zms.putRole(mockDomRsrcCtx, domain, "Role1", null, role);
            fail("requesterror not thrown by putRole.");
        } catch (ResourceException ex) {
            assertEquals(400, ex.getCode());
            assertTrue(ex.getMessage().contains("Audit reference required"));
        } finally {
            zms.deleteTopLevelDomain(mockDomRsrcCtx, domain, auditRef);
        }
    }

    @Test
    public void testCreateRoleMismatchName() {

        TopLevelDomain dom1 = createTopLevelDomainObject(
                "CreateMismatchRoleDom1", "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        Role role1 = createRoleObject("CreateMismatchRoleDom1", "Role1", null,
                "user.joe", "user.jane");

        try {
            zms.putRole(mockDomRsrcCtx, "CreateMismatchRoleDom1",
                    "CreateMismatchRoleDom1.Role1", auditRef, role1);
            fail();
        } catch (ResourceException ex) {
            assertEquals(400, ex.getCode());
        }

        zms.deleteTopLevelDomain(mockDomRsrcCtx, "CreateMismatchRoleDom1", auditRef);
    }
    
    @Test
    public void testCreateRoleInvalidName() {

        TopLevelDomain dom1 = createTopLevelDomainObject(
                "CreateRoleInvalidNameDom1", "Test Domain1", "testOrg",
                adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        Role role1 = new Role();
        role1.setName("Role1");

        try {
            zms.putRole(mockDomRsrcCtx, "CreateRoleInvalidNameDom1", "Role111", auditRef, role1);
            fail();
        } catch (ResourceException ex) {
            assertEquals(400, ex.getCode());
        }

        zms.deleteTopLevelDomain(mockDomRsrcCtx,"CreateRoleInvalidNameDom1", auditRef);
    }

    @Test
    public void testCreateRoleInvalidStruct() {

        TopLevelDomain dom1 = createTopLevelDomainObject(
                "CreateRoleInvalidStructDom1", "Test Domain1", "testOrg",
                adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        Role role1 = new Role();

        try {
            zms.putRole(mockDomRsrcCtx, "CreateRoleInvalidStructDom1", "Role1", auditRef, role1);
            fail();
        } catch (ResourceException ex) {
            assertEquals(400, ex.getCode());
        }

        zms.deleteTopLevelDomain(mockDomRsrcCtx,"CreateRoleInvalidStructDom1", auditRef);
    }

    @Test
    public void testCreateRoleInvalidMembers() {

        TopLevelDomain dom1 = createTopLevelDomainObject(
                "CreateInvalidMemberRoleDom1", "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        Role role1 = createRoleObject("CreateInvalidMemberRoleDom1", "Role1", null,
                "user.joe", "jane");

        try {
            zms.putRole(mockDomRsrcCtx, "CreateInvalidMemberRoleDom1", "Role1", auditRef, role1);
            fail();
        } catch (ResourceException ex) {
            assertEquals(404, ex.getCode());
        }

        Role role2 = createRoleObject("CreateInvalidMemberRoleDom1", "Role2", null,
                "joe", "user.jane");

        try {
            zms.putRole(mockDomRsrcCtx, "CreateInvalidMemberRoleDom1", "Role2", auditRef, role2);
            fail();
        } catch (ResourceException ex) {
            assertEquals(404, ex.getCode());
        }

        zms.deleteTopLevelDomain(mockDomRsrcCtx,"CreateInvalidMemberRoleDom1", auditRef);
    }
    
    @Test
    public void testCreateRoleBothMemberAndTrust() {

        String domainName = "rolebothmemberandtrust";
        TopLevelDomain dom1 = createTopLevelDomainObject(domainName,
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        Role role1 = createRoleObject(domainName, "Role1", "sys.auth",
                "user.joe", "user.jane");

        try {
            zms.putRole(mockDomRsrcCtx, domainName, "Role1", auditRef, role1);
            fail();
        } catch (ResourceException ex) {
            assertEquals(400, ex.getCode());
        }
        
        zms.deleteTopLevelDomain(mockDomRsrcCtx, domainName, auditRef);
    }
    
    @Test
    public void testCreateRoleTrustItself() {

        String domainName = "roletrustitself";
        TopLevelDomain dom1 = createTopLevelDomainObject(domainName,
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        Role role1 = createRoleObject(domainName, "Role1", domainName,
                null, null);

        try {
            zms.putRole(mockDomRsrcCtx, domainName, "Role1", auditRef, role1);
            fail();
        } catch (ResourceException ex) {
            assertEquals(400, ex.getCode());
        }
        
        zms.deleteTopLevelDomain(mockDomRsrcCtx, domainName, auditRef);
    }
    
    @Test
    public void testCreateDuplicateMemberRole() {

        TopLevelDomain dom1 = createTopLevelDomainObject("CreateDuplicateMemberRoleDom1",
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        Role role1 = createRoleObject("CreateDuplicateMemberRoleDom1", "Role1", null,
                "user.joe", "user.joe");
        zms.putRole(mockDomRsrcCtx, "CreateDuplicateMemberRoleDom1", "Role1", auditRef, role1);

        Role role = zms.getRole(mockDomRsrcCtx, "CreateDuplicateMemberRoleDom1", "Role1", false, false, false);
        assertNotNull(role);

        assertEquals(role.getName(), "CreateDuplicateMemberRoleDom1:role.Role1".toLowerCase());
        assertNull(role.getTrust());
        List<RoleMember> members = role.getRoleMembers();
        assertNotNull(members);
        assertEquals(members.size(), 1);
        assertEquals("user.joe", members.get(0).getMemberName());

        zms.deleteTopLevelDomain(mockDomRsrcCtx,"CreateDuplicateMemberRoleDom1", auditRef);
    }
    
    @Test
    public void testCreateNormalizedUserMemberRole() {

        TopLevelDomain dom1 = createTopLevelDomainObject("CreateNormalizedUserMemberRoleDom1",
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        ArrayList<RoleMember> roleMembers = new ArrayList<>();
        roleMembers.add(new RoleMember().setMemberName("user.joe"));
        roleMembers.add(new RoleMember().setMemberName("user.joe"));
        roleMembers.add(new RoleMember().setMemberName("user.joe"));
        roleMembers.add(new RoleMember().setMemberName("user.jane"));
        
        Role role1 = createRoleObject("CreateNormalizedUserMemberRoleDom1", "Role1", 
                null, roleMembers);
        zms.putRole(mockDomRsrcCtx, "CreateNormalizedUserMemberRoleDom1", "Role1", auditRef, role1);

        Role role = zms.getRole(mockDomRsrcCtx, "CreateNormalizedUserMemberRoleDom1", "Role1", false, false, false);
        assertNotNull(role);

        assertEquals(role.getName(), "CreateNormalizedUserMemberRoleDom1:role.Role1".toLowerCase());
        List<RoleMember> members = role.getRoleMembers();
        assertNotNull(members);
        assertEquals(members.size(), 2);
        List<String> checkList = new ArrayList<>();
        checkList.add("user.joe");
        checkList.add("user.jane");
        checkRoleMember(checkList, members);

        zms.deleteTopLevelDomain(mockDomRsrcCtx,"CreateNormalizedUserMemberRoleDom1", auditRef);
    }
    
    @Test
    public void testCreateNormalizedServiceMemberRole() {

        TopLevelDomain dom1 = createTopLevelDomainObject("CreateNormalizedServiceMemberRoleDom1",
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        TopLevelDomain dom2 = createTopLevelDomainObject("coretech",
                "Test Domain2", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom2);
        
        SubDomain subDom2 = createSubDomainObject("storage", "coretech",
                "Test Domain2", "testOrg", adminUser);
        zms.postSubDomain(mockDomRsrcCtx, "coretech", auditRef, subDom2);
        
        SubDomain subDom3 = createSubDomainObject("user1", "user",
                "Test Domain2", "testOrg", adminUser);
        zms.postSubDomain(mockDomRsrcCtx, "user", auditRef, subDom3);
        
        SubDomain subDom4 = createSubDomainObject("dom1", "user.user1",
                "Test Domain2", "testOrg", adminUser);
        zms.postSubDomain(mockDomRsrcCtx, "user.user1", auditRef, subDom4);
        
        ArrayList<RoleMember> roleMembers = new ArrayList<>();
        roleMembers.add(new RoleMember().setMemberName("coretech.storage"));
        roleMembers.add(new RoleMember().setMemberName("coretech.storage"));
        roleMembers.add(new RoleMember().setMemberName("user.user1.dom1.api"));
        
        Role role1 = createRoleObject("CreateNormalizedServiceMemberRoleDom1", "Role1", 
                null, roleMembers);
        zms.putRole(mockDomRsrcCtx, "CreateNormalizedServiceMemberRoleDom1", "Role1", auditRef, role1);

        Role role = zms.getRole(mockDomRsrcCtx, "CreateNormalizedServiceMemberRoleDom1", "Role1", false, false, false);
        assertNotNull(role);

        assertEquals(role.getName(), "CreateNormalizedServiceMemberRoleDom1:role.Role1".toLowerCase());
        List<RoleMember> members = role.getRoleMembers();
        assertNotNull(members);
        assertEquals(members.size(), 2);
        List<String> checkList = new ArrayList<>();
        checkList.add("coretech.storage");
        checkList.add("user.user1.dom1.api");
        checkRoleMember(checkList, members);

        zms.deleteSubDomain(mockDomRsrcCtx, "user.user1", "dom1", auditRef);
        zms.deleteSubDomain(mockDomRsrcCtx, "user", "user1", auditRef);
        zms.deleteSubDomain(mockDomRsrcCtx, "coretech", "storage", auditRef);
        zms.deleteTopLevelDomain(mockDomRsrcCtx, "coretech", auditRef);
        zms.deleteTopLevelDomain(mockDomRsrcCtx,"CreateNormalizedServiceMemberRoleDom1", auditRef);
    }
    
    @Test
    public void testCreateNormalizedCombinedMemberRole() {

        TopLevelDomain dom1 = createTopLevelDomainObject("CreateNormalizedCombinedMemberRoleDom1",
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        TopLevelDomain dom2 = createTopLevelDomainObject("coretech",
                "Test Domain2", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom2);
        
        SubDomain subDom2 = createSubDomainObject("storage", "coretech",
                "Test Domain2", "testOrg", adminUser);
        zms.postSubDomain(mockDomRsrcCtx, "coretech", auditRef, subDom2);
        
        SubDomain subDom3 = createSubDomainObject("user1", "user",
                "Test Domain2", "testOrg", adminUser);
        zms.postSubDomain(mockDomRsrcCtx, "user", auditRef, subDom3);
        
        SubDomain subDom4 = createSubDomainObject("dom1", "user.user1",
                "Test Domain2", "testOrg", adminUser);
        zms.postSubDomain(mockDomRsrcCtx, "user.user1", auditRef, subDom4);
        
        ArrayList<RoleMember> roleMembers = new ArrayList<>();
        roleMembers.add(new RoleMember().setMemberName("user.joe"));
        roleMembers.add(new RoleMember().setMemberName("user.joe"));
        roleMembers.add(new RoleMember().setMemberName("user.joe"));
        roleMembers.add(new RoleMember().setMemberName("user.jane"));
        roleMembers.add(new RoleMember().setMemberName("coretech.storage"));
        roleMembers.add(new RoleMember().setMemberName("coretech.storage"));
        roleMembers.add(new RoleMember().setMemberName("user.user1.dom1.api"));
        
        Role role1 = createRoleObject("CreateNormalizedCombinedMemberRoleDom1", "Role1", 
                null, roleMembers);
        zms.putRole(mockDomRsrcCtx, "CreateNormalizedCombinedMemberRoleDom1", "Role1", auditRef, role1);

        Role role = zms.getRole(mockDomRsrcCtx, "CreateNormalizedCombinedMemberRoleDom1", "Role1", false, false, false);
        assertNotNull(role);

        assertEquals(role.getName(), "CreateNormalizedCombinedMemberRoleDom1:role.Role1".toLowerCase());
        List<RoleMember> members = role.getRoleMembers();
        assertNotNull(members);
        assertEquals(members.size(), 4);
        List<String> checkList = new ArrayList<>();
        checkList.add("user.joe");
        checkList.add("user.jane");
        checkList.add("coretech.storage");
        checkList.add("user.user1.dom1.api");
        checkRoleMember(checkList, members);

        zms.deleteSubDomain(mockDomRsrcCtx, "user.user1", "dom1", auditRef);
        zms.deleteSubDomain(mockDomRsrcCtx, "user", "user1", auditRef);
        zms.deleteSubDomain(mockDomRsrcCtx, "coretech", "storage", auditRef);
        zms.deleteTopLevelDomain(mockDomRsrcCtx, "coretech", auditRef);
        zms.deleteTopLevelDomain(mockDomRsrcCtx,"CreateNormalizedCombinedMemberRoleDom1", auditRef);
    }
    
    @Test
    public void testDeleteRole() {

        TopLevelDomain dom1 = createTopLevelDomainObject("DelRoleDom1",
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        Role role1 = createRoleObject("DelRoleDom1", "Role1", null, "user.joe",
                "user.jane");
        zms.putRole(mockDomRsrcCtx, "DelRoleDom1", "Role1", auditRef, role1);

        Role role2 = createRoleObject("DelRoleDom1", "Role2", null, "user.joe",
                "user.jane");
        zms.putRole(mockDomRsrcCtx, "DelRoleDom1", "Role2", auditRef, role2);

        RoleList roleList = zms.getRoleList(mockDomRsrcCtx, "DelRoleDom1", null, null);
        assertNotNull(roleList);

        // our role count is +1 because of the admin role
        assertEquals(roleList.getNames().size(), 3);

        zms.deleteRole(mockDomRsrcCtx,"DelRoleDom1", "Role1", auditRef);

        roleList = zms.getRoleList(mockDomRsrcCtx, "DelRoleDom1", null, null);
        assertNotNull(roleList);

        // our role count is +1 because of the admin role
        assertEquals(roleList.getNames().size(), 2);

        assertFalse(roleList.getNames().contains("Role1".toLowerCase()));
        assertTrue(roleList.getNames().contains("Role2".toLowerCase()));

        zms.deleteTopLevelDomain(mockDomRsrcCtx,"DelRoleDom1", auditRef);
    }

    @Test
    public void testDeleteRoleMissingAuditRef() {
        String domain = "testDeleteRoleMissingAuditRef";
        TopLevelDomain dom = createTopLevelDomainObject(
                domain, "Test Domain1", "testOrg", adminUser);
        dom.setAuditEnabled(true);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom);

        Role role = createRoleObject(
            domain, "Role1", null, "user.joe", "user.jane");
        zms.putRole(mockDomRsrcCtx, domain, "Role1", auditRef, role);

        try {
            zms.deleteRole(mockDomRsrcCtx, domain, "Role1", null);
            fail("requesterror not thrown by deleteRole.");
        } catch (ResourceException ex) {
            assertEquals(400, ex.getCode());
            assertTrue(ex.getMessage().contains("Audit reference required"));
        } finally {
            zms.deleteTopLevelDomain(mockDomRsrcCtx, domain, auditRef);
        }
    }

    @Test
    public void testDeleteRoleThrowException() {

        TestAuditLogger alogger = new TestAuditLogger();
        ZMSImpl zmsImpl = getZmsImpl(alogger);

        String domainName = "DomainName1";
        String roleName = "RoleName1";
        try {
            zmsImpl.deleteRole(mockDomRsrcCtx,domainName, roleName, auditRef);
            fail("notfounderror not thrown.");
        } catch (ResourceException e) {
            assertEquals(e.getCode(), 404);
        }
    }
        
    @Test
    public void testDeleteAdminRole() {

        TopLevelDomain dom1 = createTopLevelDomainObject("DelAdminRoleDom1",
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        try {
            zms.deleteRole(mockDomRsrcCtx,"DelAdminRoleDom1", "admin", auditRef);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 400);
        }

        zms.deleteTopLevelDomain(mockDomRsrcCtx,"DelAdminRoleDom1", auditRef);
    }

    @Test
    public void testGetOverdueReview() {
        TopLevelDomain dom1 = createTopLevelDomainObject("test-domain1",
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        long currentTimeMillis = System.currentTimeMillis();
        Timestamp oldTimestamp = Timestamp.fromMillis(currentTimeMillis - 60000);
        Timestamp futureTimestamp = Timestamp.fromMillis(currentTimeMillis + 60000);

        List<RoleMember> roleMembers = new ArrayList<>();
        roleMembers.add(new RoleMember().setMemberName("user.overduereview1").setReviewReminder(oldTimestamp));
        roleMembers.add(new RoleMember().setMemberName("user.overduereview2").setReviewReminder(oldTimestamp));
        roleMembers.add(new RoleMember().setMemberName("user.futurereview1").setReviewReminder(futureTimestamp));
        roleMembers.add(new RoleMember().setMemberName("user.noreview1"));

        Role role1 = createRoleObject("test-domain1", "Role1",
                null, roleMembers);

        zms.putRole(mockDomRsrcCtx, "test-domain1", "Role1", auditRef, role1);

        DomainRoleMembers responseMembers = zms.getOverdueReview(mockDomRsrcCtx, "test-domain1");
        assertEquals("test-domain1", responseMembers.getDomainName());
        List<DomainRoleMember> responseRoleMemberList = responseMembers.getMembers();
        assertEquals(responseRoleMemberList.size(), 2);
        assertEquals(responseRoleMemberList.get(0).getMemberName(), "user.overduereview1");
        assertEquals(responseRoleMemberList.get(1).getMemberName(), "user.overduereview2");

        zms.deleteTopLevelDomain(mockDomRsrcCtx,"test-domain1", auditRef);
    }

    @Test
    public void testGetOverdueReviewThrowException() {

        final String domainName = "test-domain1";

        // Tests the getOverdueReview() condition : if (domain == null)...
        try {
            // Should fail because we never created this domain.
            zms.getOverdueReview(mockDomRsrcCtx, domainName);
            fail("notfounderror not thrown.");
        } catch (ResourceException e) {
            assertEquals(e.getCode(), 404);
        }
    }

    @Test
    public void testGetPrincipalRoles() {
        createDomain("domain1");
        createDomain("domain2");
        createDomain("domain3");

        String principal = "user.johndoe";

        insertRecordsForGetPrincipalRolesTest(principal);
        DomainRoleMember domainRoleMember = zms.getPrincipalRoles(mockDomRsrcCtx, principal, null);
        verifyGetPrincipalRoles(principal, domainRoleMember, true);
        domainRoleMember = zms.getPrincipalRoles(mockDomRsrcCtx, principal, "domain1");
        verifyGetPrincipalRoles(principal, domainRoleMember, false);

        zms.deleteTopLevelDomain(mockDomRsrcCtx,"domain1", auditRef);
        zms.deleteTopLevelDomain(mockDomRsrcCtx,"domain2", auditRef);
        zms.deleteTopLevelDomain(mockDomRsrcCtx,"domain3", auditRef);
    }

    @Test
    public void testGetPrincipalRolesCurrentPrincipal() {
        createDomain("domain1");
        createDomain("domain2");
        createDomain("domain3");

        String principalName = "user.johndoe";

        Authority principalAuthority = new com.yahoo.athenz.common.server.debug.DebugPrincipalAuthority();
        String unsignedCreds = "v=U1;d=user;n=johndoe";
        Principal principal = SimplePrincipal.create("user", "johndoe", unsignedCreds + ";s=signature",
                0, principalAuthority);
        assertNotNull(principal);
        ((SimplePrincipal) principal).setUnsignedCreds(unsignedCreds);

        ResourceContext rsrcCtx1 = createResourceContext(principal);

        insertRecordsForGetPrincipalRolesTest(principalName);
        DomainRoleMember domainRoleMember = zms.getPrincipalRoles(rsrcCtx1, null, null); // we'll don't pass a principal. Current user will be used
        verifyGetPrincipalRoles(principalName, domainRoleMember, true);
        domainRoleMember = zms.getPrincipalRoles(rsrcCtx1, null, "domain1"); // we'll don't pass a principal. Current user will be used
        verifyGetPrincipalRoles(principalName, domainRoleMember, false);

        zms.deleteTopLevelDomain(mockDomRsrcCtx,"domain1", auditRef);
        zms.deleteTopLevelDomain(mockDomRsrcCtx,"domain2", auditRef);
        zms.deleteTopLevelDomain(mockDomRsrcCtx,"domain3", auditRef);
    }

    private void verifyGetPrincipalRoles(String principal, DomainRoleMember domainRoleMember, boolean isAllDomains) {
        MemberRole memberRole0 = new MemberRole();
        memberRole0.setDomainName("domain1");
        memberRole0.setRoleName("role1");

        MemberRole memberRole1 = new MemberRole();
        memberRole1.setDomainName("domain1");
        memberRole1.setRoleName("role2");

        MemberRole memberRole2 = new MemberRole();
        memberRole2.setDomainName("domain3");
        memberRole2.setRoleName("role1");

        assertEquals(domainRoleMember.getMemberName(), principal);
        assertTrue(ZMSTestUtils.verifyDomainRoleMember(domainRoleMember, memberRole0));
        assertTrue(ZMSTestUtils.verifyDomainRoleMember(domainRoleMember, memberRole1));
        if (isAllDomains) {
            assertEquals(domainRoleMember.getMemberRoles().size(), 3);
            assertTrue(ZMSTestUtils.verifyDomainRoleMember(domainRoleMember, memberRole2));
        } else {
            assertEquals(domainRoleMember.getMemberRoles().size(), 2);
        }
    }

    private void insertRecordsForGetPrincipalRolesTest(String principal) {
        List<RoleMember> roleMembers = new ArrayList<>();
        roleMembers.add(new RoleMember().setMemberName("user.test1"));
        roleMembers.add(new RoleMember().setMemberName("user.test2"));
        roleMembers.add(new RoleMember().setMemberName(principal));

        // Create role1 in domain1 with members and principal
        Role role = createRoleObject("domain1", "Role1", null, roleMembers);
        zms.putRole(mockDomRsrcCtx, "domain1", "Role1", auditRef, role);

        // Create role2 in domain1 with members and principal
        role = createRoleObject("domain1", "role2", null, roleMembers);
        zms.putRole(mockDomRsrcCtx, "domain1", "Role2", auditRef, role);

        roleMembers = new ArrayList<>();
        roleMembers.add(new RoleMember().setMemberName("user.test1"));
        roleMembers.add(new RoleMember().setMemberName("user.test2"));

        // Create role1 in domain2 with members but without the principal
        role = createRoleObject("domain2", "role1", null, roleMembers);
        zms.putRole(mockDomRsrcCtx, "domain2", "Role1", auditRef, role);

        roleMembers = new ArrayList<>();
        roleMembers.add(new RoleMember().setMemberName(principal));

        // Create role1 in domain3 only principal
        role = createRoleObject("domain3", "role1", null, roleMembers);
        zms.putRole(mockDomRsrcCtx, "domain3", "Role1", auditRef, role);
    }

    private void createDomain(String domainName) {
        TopLevelDomain dom = createTopLevelDomainObject(domainName,
                "Test " + domainName, "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom);
    }

    @Test
    public void testGetMembership() {

        TopLevelDomain dom1 = createTopLevelDomainObject("MbrGetRoleDom1",
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        Role role1 = createRoleObject("MbrGetRoleDom1", "Role1", null,
                "user.joe", "user.jane");
        zms.putRole(mockDomRsrcCtx, "MbrGetRoleDom1", "Role1", auditRef, role1);

        Membership member1 = zms.getMembership(mockDomRsrcCtx, "MbrGetRoleDom1", "Role1",
                "user.joe", null);
        assertNotNull(member1);
        assertEquals(member1.getMemberName(), "user.joe");
        assertEquals(member1.getRoleName(), "MbrGetRoleDom1:role.Role1".toLowerCase());
        assertTrue(member1.getIsMember());

        Membership member2 = zms.getMembership(mockDomRsrcCtx, "MbrGetRoleDom1", "Role1",
                "user.doe", null);
        assertNotNull(member2);
        assertEquals(member2.getMemberName(), "user.doe");
        assertEquals(member2.getRoleName(), "MbrGetRoleDom1:role.Role1".toLowerCase());
        assertFalse(member2.getIsMember());

        zms.deleteTopLevelDomain(mockDomRsrcCtx,"MbrGetRoleDom1", auditRef);
    }

    @Test
    public void testGetMembershipThrowException() {
        String domainName = "MbrGetRoleDom1";
        String roleName = "Role1";
        String memberName1 = "user.john";
        String memberName2 = "user.jane";

        // Tests the getMembership() condition : if (domain == null)...
        try {
            // Should fail because we never created this domain.
            zms.getMembership(mockDomRsrcCtx, domainName, roleName, memberName1, null);
            fail("notfounderror not thrown.");
        } catch (ResourceException e) {
            assertEquals(e.getCode(), 404);
        }

        TopLevelDomain dom1 = createTopLevelDomainObject(domainName,
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        // Tests the getMembership() condition: if (collection == null)...
        try {
            // Should fail because we never added a role to this domain.
            zms.getMembership(mockDomRsrcCtx, domainName, roleName, memberName1, null);
            fail("notfounderror not thrown.");
        } catch (ResourceException e) {
            assertEquals(e.getCode(), 404);
        }

        // Tests the getMembership() condition: if (role == null)...
        try {
            String missingRoleName = "Role2";

            Role role1 = createRoleObject("MbrGetRoleDom1", "Role1", null,
                    memberName1, memberName2);
            zms.putRole(mockDomRsrcCtx, domainName, roleName, auditRef, role1);

            // Trying to find a non-existent role.
            zms.getMembership(mockDomRsrcCtx, domainName, missingRoleName, memberName1, null);
            fail("notfounderror not thrown.");
        } catch (ResourceException e) {
            assertEquals(e.getCode(), 404);
        }

        zms.deleteTopLevelDomain(mockDomRsrcCtx,domainName, auditRef);
    }

    @Test
    public void testPutMembership() {

        TestAuditLogger alogger = new TestAuditLogger();
        ZMSImpl zmsImpl = getZmsImpl(alogger);

        Mockito.when(mockDomRsrcCtx.getApiName())
                .thenReturn("posttopleveldomain")
                .thenReturn("posttopleveldomain")
                .thenReturn("postsubdomain")
                .thenReturn("putrole")
                .thenReturn("putmembership");
        TopLevelDomain dom1 = createTopLevelDomainObject("MbrAddDom1",
                "Test Domain1", "testOrg", "user.user1");
        zmsImpl.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        TopLevelDomain dom2 = createTopLevelDomainObject("coretech",
                "Test Domain2", "testOrg", adminUser);
        zmsImpl.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom2);
        
        SubDomain subDom2 = createSubDomainObject("storage", "coretech",
                "Test Domain2", "testOrg", adminUser);
        zmsImpl.postSubDomain(mockDomRsrcCtx, "coretech", auditRef, subDom2);
        
        Role role1 = createRoleObject("MbrAddDom1", "Role1", null,
                "user.joe", "user.jane");
        zmsImpl.putRole(mockDomRsrcCtx, "MbrAddDom1", "Role1", auditRef, role1);
        
        Membership mbr = generateMembership("Role1", "user.doe");
        zmsImpl.putMembership(mockDomRsrcCtx, "MbrAddDom1", "Role1", "user.doe", auditRef, mbr);

        // check audit log msg for putRole
        boolean foundError = false;
        List<String> aLogMsgs = alogger.getLogMsgList();
        System.err.println("testPutMembership: Number of lines: " + aLogMsgs.size());
        for (String msg: aLogMsgs) {
            if (!msg.contains("WHAT-api=(putmembership)")) {
                continue;
            }
            int index = msg.indexOf("WHAT-details=(");
            assertTrue(index != -1, msg);
            int index2 = msg.indexOf("{\"member\": \"user.doe\", \"approved\": true, \"system-disabled\": 0}");
            assertTrue(index2 > index, msg);
            foundError = true;
            break;
        }
        assertTrue(foundError);
        
        aLogMsgs.clear();
        mbr = generateMembership("Role1", "coretech.storage");
        zmsImpl.putMembership(mockDomRsrcCtx, "MbrAddDom1", "Role1", "coretech.storage", auditRef, mbr);

        Role role = zmsImpl.getRole(mockDomRsrcCtx, "MbrAddDom1", "Role1", false, false, false);
        assertNotNull(role);

        List<RoleMember> members = role.getRoleMembers();
        assertNotNull(members);
        assertEquals(members.size(), 4);

        List<String> checkList = new ArrayList<>();
        checkList.add("user.joe");
        checkList.add("user.jane");
        checkList.add("user.doe");
        checkList.add("coretech.storage");
        checkRoleMember(checkList, members);

        foundError = false;
        System.err.println("testPutMembership: now Number of lines: " + aLogMsgs.size());
        for (String msg: aLogMsgs) {
            if (!msg.contains("WHAT-api=(putmembership)")) {
                continue;
            }
            int index = msg.indexOf("WHAT-details=(");
            assertTrue(index != -1, msg);
            int index2 = msg.indexOf("{\"member\": \"coretech.storage\", \"approved\": true, \"system-disabled\": 0}");
            assertTrue(index2 > index, msg);
            foundError = true;
            break;
        }
        assertTrue(foundError);

        // enable user validation for the test

        zmsImpl.userAuthority = new TestUserPrincipalAuthority();
        zmsImpl.validateUserRoleMembers = true;

        // valid users no exception

        mbr = generateMembership("role1", "user.joe");
        zmsImpl.putMembership(mockDomRsrcCtx, "MbrAddDom1", "role1", "user.joe", auditRef, mbr);

        // invalid user with exception

        mbr = generateMembership("role1", "user.john");
        try {
            zmsImpl.putMembership(mockDomRsrcCtx, "MbrAddDom1", "role1", "user.john", auditRef, mbr);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), ResourceException.BAD_REQUEST);
        }

        zmsImpl.deleteSubDomain(mockDomRsrcCtx, "coretech", "storage", auditRef);
        zmsImpl.deleteTopLevelDomain(mockDomRsrcCtx, "coretech", auditRef);
        zmsImpl.deleteTopLevelDomain(mockDomRsrcCtx,"MbrAddDom1", auditRef);
    }

    @Test
    public void testPutMembershipExpiration() {

        Mockito.when(mockDomRsrcCtx.getApiName())
                .thenReturn("posttopleveldomain")
                .thenReturn("posttopleveldomain")
                .thenReturn("postsubdomain")
                .thenReturn("putrole")
                .thenReturn("putmembership")
                .thenReturn("deleteSubDomain")
                .thenReturn("deleteTopLevelDomain")
                .thenReturn("deleteTopLevelDomain");

        String domainName = "testPutMembershipExpiration";
        TopLevelDomain dom1 = createTopLevelDomainObject(domainName,
                "Test Domain1", "testOrg", "user.user1");
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        TopLevelDomain dom2 = createTopLevelDomainObject("coretech",
                "Test Domain2", "testOrg", adminUser);
        try {
            zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom2);
        } catch (ResourceException ex) {
            assertTrue(ex.getMessage().contains("coretech - already exists"));
        }
        
        SubDomain subDom2 = createSubDomainObject("storage", "coretech",
                "Test Domain2", "testOrg", adminUser);
        zms.postSubDomain(mockDomRsrcCtx, "coretech", auditRef, subDom2);
        
        Role role1 = createRoleObject(domainName, "Role1", null,
                "user.joe", "user.jane");
        zms.putRole(mockDomRsrcCtx, domainName, "Role1", auditRef, role1);
        
        Timestamp expired = Timestamp.fromMillis(System.currentTimeMillis() - 100);
        Timestamp notExpired = Timestamp.fromMillis(System.currentTimeMillis()
                + TimeUnit.HOURS.toMillis(1));
        
        Membership mbr = generateMembership("Role1", "user.doe", expired);
        zms.putMembership(mockDomRsrcCtx, domainName, "Role1", "user.doe", auditRef, mbr);
        Membership expiredMember = zms.getMembership(mockDomRsrcCtx, domainName,
                "Role1", "user.doe", null);
        
        mbr = generateMembership("Role1", "coretech.storage", notExpired);
        zms.putMembership(mockDomRsrcCtx, domainName, "Role1", "coretech.storage", auditRef, mbr);
        Membership notExpiredMember = zms.getMembership(mockDomRsrcCtx, domainName,
                "Role1", "coretech.storage", null);

        Role role = zms.getRole(mockDomRsrcCtx, domainName, "Role1", false, false, false);
        assertNotNull(role);

        List<RoleMember> members = role.getRoleMembers();
        assertNotNull(members);
        assertEquals(members.size(), 4);

        List<String> checkList = new ArrayList<>();
        checkList.add("user.joe");
        checkList.add("user.jane");
        checkList.add("user.doe");
        checkList.add("coretech.storage");
        checkRoleMember(checkList, role.getRoleMembers());
        
        for (RoleMember roleMember: members) {
            if (roleMember.getMemberName().equalsIgnoreCase("user.doe")) {
                Timestamp actual = roleMember.getExpiration();
                assertNotNull(actual);
                assertEquals(actual, expired);
            }
            if (roleMember.getMemberName().equalsIgnoreCase("coretech.storage")) {
                Timestamp actual = roleMember.getExpiration();
                assertNotNull(actual);
                assertEquals(actual, notExpired);
            }
        }
        
        assertFalse(expiredMember.getIsMember());
        assertTrue(notExpiredMember.getIsMember());
        
        zms.deleteSubDomain(mockDomRsrcCtx, "coretech", "storage", auditRef);
        zms.deleteTopLevelDomain(mockDomRsrcCtx, "coretech", auditRef);
        zms.deleteTopLevelDomain(mockDomRsrcCtx,domainName, auditRef);
    }
    
    @Test
    public void testPutMembershipEmptyRoleMembers() {

        TopLevelDomain dom1 = createTopLevelDomainObject("MbrAddDom1EmptyRole",
                "Test Domain1", "testOrg", "user.user1");
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        Role role1 = new Role();
        role1.setName(ZMSUtils.roleResourceName("MbrAddDom1EmptyRole", "Role1"));
        zms.putRole(mockDomRsrcCtx, "MbrAddDom1EmptyRole", "Role1", auditRef, role1);
        
        Membership mbr = generateMembership("Role1", "user.doe");
        zms.putMembership(mockDomRsrcCtx, "MbrAddDom1EmptyRole", "Role1", "user.doe", auditRef, mbr);

        Role role = zms.getRole(mockDomRsrcCtx, "MbrAddDom1EmptyRole", "Role1", false, false, false);
        assertNotNull(role);

        List<RoleMember> members = role.getRoleMembers();
        assertNotNull(members);
        assertEquals(members.size(), 1);

        assertEquals("user.doe", members.get(0).getMemberName());
        
        zms.deleteTopLevelDomain(mockDomRsrcCtx,"MbrAddDom1EmptyRole", auditRef);
    }
    
    @Test
    public void testPutMembershipMissingAuditRef() {

        TestAuditLogger alogger = new TestAuditLogger();
        ZMSImpl zmsImpl = getZmsImpl(alogger);

        String domain = "testPutMembershipMissingAuditRef";
        TopLevelDomain dom = createTopLevelDomainObject(
                domain, "Test Domain1", "testOrg", "user.user1");
        dom.setAuditEnabled(true);
        zmsImpl.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom);

        Role role = createRoleObject(
            domain, "Role1", null, "user.joe", "user.jane");
        zmsImpl.putRole(mockDomRsrcCtx, domain, "Role1", auditRef, role);

        Membership mbr = generateMembership("Role1", "user.john");
        try {
            zmsImpl.putMembership(mockDomRsrcCtx, domain, "Role1", "user.john", null, mbr);
            fail("requesterror not thrown by putMembership.");
        } catch (ResourceException ex) {
            assertEquals(400, ex.getCode());
            assertTrue(ex.getMessage().contains("Audit reference required"));
        } finally {
            zmsImpl.deleteTopLevelDomain(mockDomRsrcCtx, domain, auditRef);
        }
    }

    @Test
    public void testPutMembershipNormalizedUser() {

        TopLevelDomain dom1 = createTopLevelDomainObject("MbrAddDom2",
                "Test Domain1", "testOrg", "user.user1");
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        TopLevelDomain dom2 = createTopLevelDomainObject("coretech",
                "Test Domain2", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom2);
        
        SubDomain subDom2 = createSubDomainObject("storage", "coretech",
                "Test Domain2", "testOrg", adminUser);
        zms.postSubDomain(mockDomRsrcCtx, "coretech", auditRef, subDom2);
        
        Role role1 = createRoleObject("MbrAddDom2", "Role1", null,
                "coretech.storage", "user.jane");
        zms.putRole(mockDomRsrcCtx, "MbrAddDom2", "Role1", auditRef, role1);
        
        Membership mbr = generateMembership("Role1", "user.doe");
        zms.putMembership(mockDomRsrcCtx, "MbrAddDom2", "Role1", "user.doe", auditRef, mbr);

        Role role = zms.getRole(mockDomRsrcCtx, "MbrAddDom2", "Role1", false, false, false);
        assertNotNull(role);

        List<RoleMember> members = role.getRoleMembers();
        assertNotNull(members);
        assertEquals(members.size(), 3);

        List<String> checkList = new ArrayList<>();
        checkList.add("coretech.storage");
        checkList.add("user.jane");
        checkList.add("user.doe");
        checkRoleMember(checkList, members);
        
        zms.deleteSubDomain(mockDomRsrcCtx, "coretech", "storage", auditRef);
        zms.deleteTopLevelDomain(mockDomRsrcCtx, "coretech", auditRef);
        zms.deleteTopLevelDomain(mockDomRsrcCtx, "MbrAddDom2", auditRef);
    }
    
    @Test
    public void testPutMembershipNormalizedUseruser() {

        TopLevelDomain dom1 = createTopLevelDomainObject("MbrAddDom3",
                "Test Domain1", "testOrg", "user.user1");
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        TopLevelDomain dom2 = createTopLevelDomainObject("coretech",
                "Test Domain2", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom2);
        
        SubDomain subDom2 = createSubDomainObject("storage", "coretech",
                "Test Domain2", "testOrg", adminUser);
        zms.postSubDomain(mockDomRsrcCtx, "coretech", auditRef, subDom2);
        
        Role role1 = createRoleObject("MbrAddDom3", "Role1", null,
                "coretech.storage", "user.jane");
        zms.putRole(mockDomRsrcCtx, "MbrAddDom3", "Role1", auditRef, role1);
        
        Membership mbr = generateMembership("Role1", "user.doe");
        zms.putMembership(mockDomRsrcCtx, "MbrAddDom3", "Role1", "user.doe", auditRef, mbr);

        Role role = zms.getRole(mockDomRsrcCtx, "MbrAddDom3", "Role1", false, false, false);
        assertNotNull(role);

        List<RoleMember> members = role.getRoleMembers();
        assertNotNull(members);
        assertEquals(members.size(), 3);

        List<String> checkList = new ArrayList<>();
        checkList.add("coretech.storage");
        checkList.add("user.jane");
        checkList.add("user.doe");
        checkRoleMember(checkList, members);
        
        zms.deleteSubDomain(mockDomRsrcCtx, "coretech", "storage", auditRef);
        zms.deleteTopLevelDomain(mockDomRsrcCtx, "coretech", auditRef);
        zms.deleteTopLevelDomain(mockDomRsrcCtx, "MbrAddDom3", auditRef);
    }

    @Test
    public void testPutMembershipNormalizedService() {

        TopLevelDomain dom1 = createTopLevelDomainObject("MbrAddDom4",
                "Test Domain1", "testOrg", "user.user1");
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        TopLevelDomain dom2 = createTopLevelDomainObject("coretech",
                "Test Domain2", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom2);
        
        SubDomain subDom2 = createSubDomainObject("storage", "coretech",
                "Test Domain2", "testOrg", adminUser);
        zms.postSubDomain(mockDomRsrcCtx, "coretech", auditRef, subDom2);
        
        TopLevelDomain dom3 = createTopLevelDomainObject("weather",
                "Test Domain2", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom3);
        
        SubDomain subDom3 = createSubDomainObject("storage", "weather",
                "Test Domain2", "testOrg", adminUser);
        zms.postSubDomain(mockDomRsrcCtx, "weather", auditRef, subDom3);
        
        Role role1 = createRoleObject("MbrAddDom4", "Role1", null,
                "coretech.storage", "user.jane");
        zms.putRole(mockDomRsrcCtx, "MbrAddDom4", "Role1", auditRef, role1);
 
        Membership mbr = generateMembership("Role1", "weather.storage");
        zms.putMembership(mockDomRsrcCtx, "MbrAddDom4", "Role1", "weather.storage", auditRef, mbr);

        Role role = zms.getRole(mockDomRsrcCtx, "MbrAddDom4", "Role1", false, false, false);
        assertNotNull(role);

        List<RoleMember> members = role.getRoleMembers();
        assertNotNull(members);
        assertEquals(members.size(), 3);

        List<String> checkList = new ArrayList<>();
        checkList.add("coretech.storage");
        checkList.add("user.jane");
        checkList.add("weather.storage");
        checkRoleMember(checkList, members);
        
        zms.deleteSubDomain(mockDomRsrcCtx, "weather", "storage", auditRef);
        zms.deleteTopLevelDomain(mockDomRsrcCtx, "weather", auditRef);
        zms.deleteSubDomain(mockDomRsrcCtx, "coretech", "storage", auditRef);
        zms.deleteTopLevelDomain(mockDomRsrcCtx, "coretech", auditRef);
        zms.deleteTopLevelDomain(mockDomRsrcCtx, "MbrAddDom4", auditRef);
    }

    @Test
    public void testPutMembershipRoleNotPresent() {

        TopLevelDomain dom1 = createTopLevelDomainObject("MbrAddDomNoRole",
                "Test Domain1", "testOrg", "user.user1");
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        TopLevelDomain dom2 = createTopLevelDomainObject("coretech",
                "Test Domain2", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom2);
        
        SubDomain subDom2 = createSubDomainObject("storage", "coretech",
                "Test Domain2", "testOrg", adminUser);
        zms.postSubDomain(mockDomRsrcCtx, "coretech", auditRef, subDom2);
        
        Role role1 = createRoleObject("MbrAddDomNoRole", "Role1", null,
                "coretech.storage", "user.jane");
        zms.putRole(mockDomRsrcCtx, "MbrAddDomNoRole", "Role1", auditRef, role1);

        // membership object with only member

        Membership mbr = new Membership();
        mbr.setMemberName("user.joe");

        zms.putMembership(mockDomRsrcCtx, "MbrAddDomNoRole", "Role1", "user.joe", auditRef, mbr);

        Role role = zms.getRole(mockDomRsrcCtx, "MbrAddDomNoRole", "Role1", false, false, false);
        assertNotNull(role);

        List<RoleMember> members = role.getRoleMembers();
        assertNotNull(members);
        assertEquals(members.size(), 3);

        List<String> checkList = new ArrayList<>();
        checkList.add("coretech.storage");
        checkList.add("user.jane");
        checkList.add("user.joe");
        checkRoleMember(checkList, members);

        zms.deleteSubDomain(mockDomRsrcCtx, "coretech", "storage", auditRef);
        zms.deleteTopLevelDomain(mockDomRsrcCtx, "coretech", auditRef);
        zms.deleteTopLevelDomain(mockDomRsrcCtx, "MbrAddDomNoRole", auditRef);
    }
 
    @Test
    public void testPutMembershipInvalid() {

        TopLevelDomain dom1 = createTopLevelDomainObject("MbrAddDom5",
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);
        
        Role role1 = createRoleObject("MbrAddDom5", "Role1", null,
                "user.joe", "user.jane");
        zms.putRole(mockDomRsrcCtx, "MbrAddDom5", "Role1", auditRef, role1);
        try {
            Membership mbr = generateMembership("Role1", "coretech");
            zms.putMembership(mockDomRsrcCtx, "MbrAddDom5", "Role1", "coretech", auditRef, mbr);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 403);
        }
        
        zms.deleteTopLevelDomain(mockDomRsrcCtx, "MbrAddDom5", auditRef);
    }
    
    @Test
    public void testPutMembershipRoleMismatch() {

        TopLevelDomain dom1 = createTopLevelDomainObject("MbrAddDom6",
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        Role role1 = createRoleObject("MbrAddDom6", "Role1", null,
                "user.joe", "user.jane");
        zms.putRole(mockDomRsrcCtx, "MbrAddDom6", "Role1", auditRef, role1);
        try {
            Membership mbr = generateMembership("Role2", "user.john");
            zms.putMembership(mockDomRsrcCtx, "MbrAddDom6", "Role1", "user.john", auditRef, mbr);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 400);
        }
        
        zms.deleteTopLevelDomain(mockDomRsrcCtx, "MbrAddDom6", auditRef);
    }
    
    @Test
    public void testPutMembershipMemberMismatch() {

        TopLevelDomain dom1 = createTopLevelDomainObject("MbrAddDom7",
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        Role role1 = createRoleObject("MbrAddDom7", "Role1", null,
                "user.joe", "user.jane");
        zms.putRole(mockDomRsrcCtx, "MbrAddDom7", "Role1", auditRef, role1);
        try {
            Membership mbr = generateMembership("Role1", "user.john");
            zms.putMembership(mockDomRsrcCtx, "MbrAddDom7", "Role1", "user.johnny", auditRef, mbr);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 400);
        }
        
        zms.deleteTopLevelDomain(mockDomRsrcCtx, "MbrAddDom7", auditRef);
    }
    
    @Test
    public void testPutMembershipThrowException() {
        String domainName = "MbrGetRoleDom1";
        String roleName = "Role1";
        String memberName1 = "user.john";
        String memberName2 = "user.jane";
        String wrongDomainName = "MbrGetRoleDom2";
        
        TopLevelDomain dom1 = createTopLevelDomainObject(domainName,
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);
        
        // Tests the putMembership() condition : if (domain == null)...
        try {
            // Trying to add a wrong domain name.
            Membership mbr = generateMembership(roleName, memberName1);
            zms.putMembership(mockDomRsrcCtx, wrongDomainName, roleName, memberName1, auditRef, mbr);
            fail("notfounderror not thrown.");
        } catch (ResourceException e) {
            assertEquals(e.getCode(), 400);
        }
        
        // Tests the putMembership() condition: if (collection == null)...
        try {
            // Should fail because we never added a role resource.
            Membership mbr = generateMembership(roleName, memberName1);
            zms.putMembership(mockDomRsrcCtx, domainName, roleName, memberName1, auditRef, mbr);
            fail("notfounderror not thrown.");
        } catch (ResourceException e) {
            assertEquals(e.getCode(), 400);
        }
        
        // Tests the putMembership() condition : invalid membership object - null
        try {
            // Trying to add a wrong domain name.
            zms.putMembership(mockDomRsrcCtx, wrongDomainName, roleName, memberName1, auditRef, null);
            fail("notfounderror not thrown.");
        } catch (ResourceException e) {
            assertEquals(e.getCode(), 400);
        }
        
        // Tests the putMembership() condition : invalid membership object - missing name
        try {
            // Trying to add a wrong domain name.
            Membership mbr = new Membership();
            zms.putMembership(mockDomRsrcCtx, wrongDomainName, roleName, memberName1, auditRef, mbr);
            fail("notfounderror not thrown.");
        } catch (ResourceException e) {
            assertEquals(e.getCode(), 400);
        }
        
        // Tests the putMembership() condition: if (role == null)...
        try {
            String wrongRoleName = "Role2";
            
            Role role1 = createRoleObject(domainName, roleName, null,
                    memberName1, memberName2);
            zms.putRole(mockDomRsrcCtx, domainName, roleName, auditRef, role1);
            
            // Trying to add member to non-existent role.
            Membership mbr = generateMembership(wrongRoleName, memberName1);
            zms.putMembership(mockDomRsrcCtx, domainName, wrongRoleName, memberName1, auditRef, mbr);
            fail("notfounderror not thrown.");
        } catch (ResourceException e) {
            assertEquals(e.getCode(), 400);
        }
        
        zms.deleteTopLevelDomain(mockDomRsrcCtx, domainName, auditRef);
    }

    @Test
    public void testDeleteMembership() {

        TopLevelDomain dom1 = createTopLevelDomainObject("MbrDelDom1",
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        Role role1 = createRoleObject("MbrDelDom1", "Role1", null,
                "user.joe", "user.jane");
        zms.putRole(mockDomRsrcCtx, "MbrDelDom1", "Role1", auditRef, role1);
        zms.deleteMembership(mockDomRsrcCtx, "MbrDelDom1", "Role1", "user.joe", auditRef);

        Role role = zms.getRole(mockDomRsrcCtx, "MbrDelDom1", "Role1", false, false, false);
        assertNotNull(role);

        List<RoleMember> members = role.getRoleMembers();
        assertNotNull(members);
        assertEquals(members.size(), 1);

        boolean found = false;
        for (RoleMember member: members) {
            if (member.getMemberName().equalsIgnoreCase("user.joe")) {
                fail("delete user.joe failed");
            }
            if (member.getMemberName().equalsIgnoreCase("user.jane")) {
                found = true;
            }
        }
        if (!found) {
            fail("user.jane not found");
        }

        zms.deleteTopLevelDomain(mockDomRsrcCtx, "MbrDelDom1", auditRef);
    }

    @Test
    public void testDeleteMembershipMissingAuditRef() {
        String domain = "testDeleteMembershipMissingAuditRef";
        TopLevelDomain dom = createTopLevelDomainObject(
                domain, "Test Domain1", "testOrg", adminUser);
        dom.setAuditEnabled(true);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom);

        Role role = createRoleObject(
            domain, "Role1", null, "user.joe", "user.jane");
        zms.putRole(mockDomRsrcCtx, domain, "Role1", auditRef, role);

        try {
            zms.deleteMembership(mockDomRsrcCtx, domain, "Role1", "user.joe", null);
            fail("requesterror not thrown by deleteMembership.");
        } catch (ResourceException ex) {
            assertEquals(400, ex.getCode());
            assertTrue(ex.getMessage().contains("Audit reference required"));
        } finally {
            zms.deleteTopLevelDomain(mockDomRsrcCtx, domain, auditRef);
        }
    }

    @Test
    public void testDeleteMembershipInvalidDomain() {
        String domainName = "MbrGetRoleDom1";
        String roleName = "Role1";
        String memberName1 = "user.john";
        
        TopLevelDomain dom1 = createTopLevelDomainObject(domainName,
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);
        
        // Tests the deleteMembership() condition : if (domain == null)...
        try {
            String wrongDomainName = "MbrGetRoleDom2";
            
            // Should fail because this domain does not exist.
            zms.deleteMembership(mockDomRsrcCtx, wrongDomainName, roleName, memberName1, auditRef);
            fail("notfounderror not thrown.");
        } catch (ResourceException e) {
            assertEquals(e.getCode(), 404);
        }
        
        zms.deleteTopLevelDomain(mockDomRsrcCtx, domainName, auditRef);
    }
    
    @Test
    public void testDeleteMembershipInvalidRoleCollection() {
        String domainName = "MbrGetRoleDom1";
        String roleName = "Role1";
        String memberName1 = "user.john";
        
        TopLevelDomain dom1 = createTopLevelDomainObject(domainName,
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);
        
        // Test the deleteMembership() condition: if (collection == null)...
        try {
            // Should fail b/c a role entity was never added.
            zms.deleteMembership(mockDomRsrcCtx, domainName, roleName, memberName1, auditRef);
            fail("notfounderror not thrown.");
        } catch (ResourceException e) {
            assertEquals(e.getCode(), 404);
        }
        
        zms.deleteTopLevelDomain(mockDomRsrcCtx, domainName, auditRef);
    }
    
    @Test
    public void testDeleteMembershipInvalidRole() {
        String domainName = "MbrGetRoleDom1";
        String roleName = "Role1";
        String memberName1 = "user.john";
        String memberName2 = "user.jane";
        
        TopLevelDomain dom1 = createTopLevelDomainObject(domainName,
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);
        
        // Tests the deleteMembership() condition: if (role == null)... 
        try {
            String wrongRoleName = "Role2";
            Role role1 = createRoleObject(domainName, roleName, null,
                    memberName1, memberName2);
            zms.putRole(mockDomRsrcCtx, domainName, roleName, auditRef, role1);
            
            // Should fail b/c trying to find a non-existent role.
            zms.deleteMembership(mockDomRsrcCtx, domainName, wrongRoleName, memberName1, auditRef);
            fail("notfounderror not thrown.");
        } catch (ResourceException e) {
            assertEquals(e.getCode(), 404);
        }
        
        zms.deleteTopLevelDomain(mockDomRsrcCtx, domainName, auditRef);
    }
    
    @Test
    public void testDeleteMembershipAdminRoleSingleMember() {

        TestAuditLogger alogger = new TestAuditLogger();
        ZMSImpl zmsImpl = getZmsImpl(alogger);

        String domainName = "MbrGetRoleDom1";
        String memberName1 = "user.john";
        
        TopLevelDomain dom1 = createTopLevelDomainObject(domainName,
                "Test Domain1", "testOrg", adminUser);
        zmsImpl.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);
        
        // Test the deleteMembership() condition: if ("admin".equals(roleName))...
        try {
            String adminRoleName = "admin";
            
            List<RoleMember> members = new ArrayList<>();
            members.add(new RoleMember().setMemberName(memberName1));
            Role role1 = createRoleObject(domainName, adminRoleName, null, members);
            zmsImpl.putRole(mockDomRsrcCtx, domainName, adminRoleName, auditRef, role1);
            
            // Can not delete the last admin role.
            zmsImpl.deleteMembership(mockDomRsrcCtx, domainName, adminRoleName, memberName1, auditRef);
            fail("forbiddenerror not thrown.");
        } catch (ResourceException e) {
            assertEquals(e.getCode(), 403);
        }
        
        zmsImpl.deleteTopLevelDomain(mockDomRsrcCtx, domainName, auditRef);
    }

    @Test
    public void testDeleteMembershipNormalizedUser() {

        TopLevelDomain dom1 = createTopLevelDomainObject("MbrDelDom1",
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        Role role1 = createRoleObject("MbrDelDom1", "Role1", null,
                "user.joe", "user.jane");
        zms.putRole(mockDomRsrcCtx, "MbrDelDom1", "Role1", auditRef, role1);
        zms.deleteMembership(mockDomRsrcCtx, "MbrDelDom1", "Role1", "user.joe", auditRef);

        Role role = zms.getRole(mockDomRsrcCtx, "MbrDelDom1", "Role1", false, false, false);
        assertNotNull(role);

        List<RoleMember> members = role.getRoleMembers();
        assertNotNull(members);
        assertEquals(members.size(), 1);
        assertEquals(members.get(0).getMemberName(), "user.jane");

        zms.deleteTopLevelDomain(mockDomRsrcCtx, "MbrDelDom1", auditRef);
    }
    
    @Test
    public void testDeleteMembershipNormalizeduser() {

        TopLevelDomain dom1 = createTopLevelDomainObject("MbrDelDom1",
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        Role role1 = createRoleObject("MbrDelDom1", "Role1", null,
                "user.joe", "user.jane");
        zms.putRole(mockDomRsrcCtx, "MbrDelDom1", "Role1", auditRef, role1);
        zms.deleteMembership(mockDomRsrcCtx, "MbrDelDom1", "Role1", "user.joe", auditRef);

        Role role = zms.getRole(mockDomRsrcCtx, "MbrDelDom1", "Role1", false, false, false);
        assertNotNull(role);

        List<RoleMember> members = role.getRoleMembers();
        assertNotNull(members);
        assertEquals(members.size(), 1);
        assertEquals(members.get(0).getMemberName(), "user.jane");

        zms.deleteTopLevelDomain(mockDomRsrcCtx, "MbrDelDom1", auditRef);
    }
    
    @Test
    public void testDeleteMembershipNormalizedService() {

        TopLevelDomain dom1 = createTopLevelDomainObject("MbrDelDom1",
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        TopLevelDomain dom2 = createTopLevelDomainObject("coretech",
                "Test Domain2", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom2);
        
        SubDomain subDom2 = createSubDomainObject("storage", "coretech",
                "Test Domain2", "testOrg", adminUser);
        zms.postSubDomain(mockDomRsrcCtx, "coretech", auditRef, subDom2);
        
        Role role1 = createRoleObject("MbrDelDom1", "Role1", null,
                "user.joe", "coretech.storage");
        zms.putRole(mockDomRsrcCtx, "MbrDelDom1", "Role1", auditRef, role1);
        zms.deleteMembership(mockDomRsrcCtx, "MbrDelDom1", "Role1", "coretech.storage", auditRef);

        Role role = zms.getRole(mockDomRsrcCtx, "MbrDelDom1", "Role1", false, false, false);
        assertNotNull(role);

        List<RoleMember> members = role.getRoleMembers();
        assertNotNull(members);
        assertEquals(members.size(), 1);
        assertEquals(members.get(0).getMemberName(), "user.joe");

        zms.deleteSubDomain(mockDomRsrcCtx, "coretech", "storage", auditRef);
        zms.deleteTopLevelDomain(mockDomRsrcCtx, "coretech", auditRef);
        zms.deleteTopLevelDomain(mockDomRsrcCtx, "MbrDelDom1", auditRef);
    }

    @Test
    public void testGetPolicyList() {

        TopLevelDomain dom1 = createTopLevelDomainObject("PolicyListDom1",
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        Policy policy1 = createPolicyObject("PolicyListDom1", "Policy1");
        zms.putPolicy(mockDomRsrcCtx, "PolicyListDom1", "Policy1", auditRef, policy1);

        Policy policy2 = createPolicyObject("PolicyListDom1", "Policy2");
        zms.putPolicy(mockDomRsrcCtx, "PolicyListDom1", "Policy2", auditRef, policy2);

        Policy policy3 = createPolicyObject("PolicyListDom1", "Policy3");
        policy3.setCaseSensitive(true);
        zms.putPolicy(mockDomRsrcCtx, "PolicyListDom1", "Policy3", auditRef, policy3);

        PolicyList policyList = zms.getPolicyList(mockDomRsrcCtx, "PolicyListDom1", null, null);
        assertNotNull(policyList);

        // policy count +1 due to admin policy
        assertEquals(policyList.getNames().size(), 4);

        assertTrue(policyList.getNames().contains("Policy1".toLowerCase()));
        assertTrue(policyList.getNames().contains("Policy2".toLowerCase()));
        assertTrue(policyList.getNames().contains("Policy3".toLowerCase()));

        zms.deleteTopLevelDomain(mockDomRsrcCtx, "PolicyListDom1", auditRef);
    }

    @Test
    public void testGetPolicyListParams() {

        TopLevelDomain dom1 = createTopLevelDomainObject(
                "PolicyListParamsDom1", "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        Policy policy1 = createPolicyObject("PolicyListParamsDom1", "Policy1");
        zms.putPolicy(mockDomRsrcCtx, "PolicyListParamsDom1", "Policy1", auditRef, policy1);

        Policy policy2 = createPolicyObject("PolicyListParamsDom1", "Policy2");
        zms.putPolicy(mockDomRsrcCtx, "PolicyListParamsDom1", "Policy2", auditRef, policy2);

        PolicyList policyList = zms.getPolicyList(mockDomRsrcCtx, "PolicyListParamsDom1", null,
                "Policy1");
        assertNotNull(policyList);

        assertFalse(policyList.getNames().contains("Policy1".toLowerCase()));
        assertTrue(policyList.getNames().contains("Policy2".toLowerCase()));

        zms.deleteTopLevelDomain(mockDomRsrcCtx, "PolicyListParamsDom1", auditRef);
    }
    
    @Test
    public void testGetPolicyListThrowException() {
        try {
            zms.getPolicyList(mockDomRsrcCtx, "WrongDomainName", null, null);
            fail("notfounderror not thrown.");
        } catch (ResourceException e) {
            assertEquals(e.getCode(), 404);
        }
    }

    @Test
    public void testGetPolicy() {

        TestAuditLogger alogger = new TestAuditLogger();
        List<String> aLogMsgs = alogger.getLogMsgList();
        ZMSImpl zmsImpl = getZmsImpl(alogger);

        TopLevelDomain dom1 = createTopLevelDomainObject("PolicyGetDom1",
                "Test Domain1", "testOrg", adminUser);
        Mockito.when(mockDomRsrcCtx.getApiName()).thenReturn("posttopleveldomain").thenReturn("putpolicy");
        zmsImpl.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        Policy policy1 = createPolicyObject("PolicyGetDom1", "Policy1");
        zmsImpl.putPolicy(mockDomRsrcCtx, "PolicyGetDom1", "Policy1", auditRef, policy1);

        Policy policy = zmsImpl.getPolicy(mockDomRsrcCtx, "PolicyGetDom1", "Policy1");
        assertNotNull(policy);
        assertEquals(policy.getName(), "PolicyGetDom1:policy.Policy1".toLowerCase());

        List<Assertion> assertList = policy.getAssertions();
        assertNotNull(assertList);
        assertEquals(assertList.size(), 1);
        Assertion obj = assertList.get(0);
        assertEquals(obj.getAction(), "*");
        assertEquals(obj.getEffect(), AssertionEffect.ALLOW);
        assertEquals(obj.getResource(), "policygetdom1:*");
        assertEquals(obj.getRole(), "PolicyGetDom1:role.Role1".toLowerCase());

        boolean foundError = false;
        System.err.println("testGetPolicy: Number of lines: " + aLogMsgs.size());
        for (String msg: aLogMsgs) {
            if (!msg.contains("WHAT-api=(putpolicy)")) {
                continue;
            }
            assertTrue(msg.contains("CLIENT-IP=(" + MOCKCLIENTADDR + ")"), msg);
            int index = msg.indexOf("WHAT-details=(");
            assertTrue(index != -1, msg);
            int index2 = msg.indexOf("\"added-assertions\": [{\"role\": \"policygetdom1:role.role1\", \"action\": \"*\", \"effect\": \"ALLOW\", \"resource\": \"policygetdom1:*\"}]");
            assertTrue(index < index2, msg);
            index2 = msg.indexOf("ERROR");
            assertEquals(index2, -1, msg);
            foundError = true;
            break;
        }
        assertTrue(foundError);

        // modify the assertion: result is add of new assertion, delete of old
        //
        obj.setAction("layup");
        obj.setEffect(AssertionEffect.DENY);
        List<Assertion> assertions = new ArrayList<>();
        assertions.add(obj);
        policy1.setAssertions(assertions);
        aLogMsgs.clear();
        zmsImpl.putPolicy(mockDomRsrcCtx, "PolicyGetDom1", "Policy1", auditRef, policy1);

        foundError = false;
        System.err.println("testGetPolicy: Number of lines: " + aLogMsgs.size());
        for (String msg: aLogMsgs) {
            if (!msg.contains("WHAT-api=(putpolicy)")) {
                continue;
            }
            assertTrue(msg.contains("CLIENT-IP=(" + MOCKCLIENTADDR + ")"), msg);
            int index = msg.indexOf("WHAT-details=(");
            assertTrue(index != -1, msg);
            int index2 = msg.indexOf("\"added-assertions\": [{\"role\": \"policygetdom1:role.role1\", \"action\": \"layup\", \"effect\": \"DENY\", \"resource\": \"policygetdom1:*\"}]");
            assertTrue(index < index2, msg);
            index2 = msg.indexOf("ERROR");
            assertEquals(index2, -1, msg);
            foundError = true;
            break;
        }
        assertTrue(foundError);

        // this should throw an exception
        try {
            zmsImpl.getPolicy(mockDomRsrcCtx, "PolicyGetDom1", "Policy2");
            fail();
        } catch (Exception ex) {
            assertTrue(true);
        }

        zmsImpl.deleteTopLevelDomain(mockDomRsrcCtx, "PolicyGetDom1", auditRef);
    }

    @Test
    public void testGetPolicyCaseSensitive() {

        TestAuditLogger alogger = new TestAuditLogger();
        List<String> aLogMsgs = alogger.getLogMsgList();
        ZMSImpl zmsImpl = getZmsImpl(alogger);

        TopLevelDomain dom1 = createTopLevelDomainObject("PolicyGetDom1",
                "Test Domain1", "testOrg", adminUser);
        Mockito.when(mockDomRsrcCtx.getApiName()).thenReturn("posttopleveldomain").thenReturn("putpolicy");
        zmsImpl.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        Policy policy1 = createPolicyObject("PolicyGetDom1", "Policy1", "Role1", "ActioN1", "PolicyGetDom1:SomeResourcE", AssertionEffect.ALLOW);
        policy1.setCaseSensitive(true);
        zmsImpl.putPolicy(mockDomRsrcCtx, "PolicyGetDom1", "Policy1", auditRef, policy1);

        Policy policy = zmsImpl.getPolicy(mockDomRsrcCtx, "PolicyGetDom1", "Policy1");
        assertNotNull(policy);
        assertEquals(policy.getName(), "PolicyGetDom1:policy.Policy1".toLowerCase());

        List<Assertion> assertList = policy.getAssertions();
        assertNotNull(assertList);
        assertEquals(assertList.size(), 1);
        Assertion obj = assertList.get(0);
        assertEquals(obj.getAction(), "ActioN1");
        assertEquals(obj.getEffect(), AssertionEffect.ALLOW);
        assertEquals(obj.getResource(), "policygetdom1:SomeResourcE");
        assertEquals(obj.getRole(), "PolicyGetDom1:role.Role1".toLowerCase());

        boolean foundError = false;
        System.err.println("testGetPolicy: Number of lines: " + aLogMsgs.size());
        for (String msg: aLogMsgs) {
            if (!msg.contains("WHAT-api=(putpolicy)")) {
                continue;
            }
            assertTrue(msg.contains("CLIENT-IP=(" + MOCKCLIENTADDR + ")"), msg);
            int index = msg.indexOf("WHAT-details=(");
            assertTrue(index != -1, msg);
            int index2 = msg.indexOf("\"added-assertions\": [{\"role\": \"policygetdom1:role.role1\", \"action\": \"ActioN1\", \"effect\": \"ALLOW\", \"resource\": \"policygetdom1:SomeResourcE\"}]");
            assertTrue(index < index2, msg);
            index2 = msg.indexOf("ERROR");
            assertEquals(index2, -1, msg);
            foundError = true;
            break;
        }
        assertTrue(foundError);

        // modify the assertion: result is add of new assertion, delete of old
        //
        obj.setAction("layup");
        // We'll set the policy to not be case-sensitive. So even though we didn't override the resource name, it will be lower-cased
        policy1.setCaseSensitive(false);
        obj.setEffect(AssertionEffect.DENY);
        List<Assertion> assertions = new ArrayList<>();
        assertions.add(obj);
        policy1.setAssertions(assertions);
        aLogMsgs.clear();
        zmsImpl.putPolicy(mockDomRsrcCtx, "PolicyGetDom1", "Policy1", auditRef, policy1);

        foundError = false;
        System.err.println("testGetPolicy: Number of lines: " + aLogMsgs.size());
        for (String msg: aLogMsgs) {
            if (!msg.contains("WHAT-api=(putpolicy)")) {
                continue;
            }
            assertTrue(msg.contains("CLIENT-IP=(" + MOCKCLIENTADDR + ")"), msg);
            int index = msg.indexOf("WHAT-details=(");
            assertTrue(index != -1, msg);
            int index2 = msg.indexOf("\"added-assertions\": [{\"role\": \"policygetdom1:role.role1\", \"action\": \"layup\", \"effect\": \"DENY\", \"resource\": \"policygetdom1:someresource\"}]");
            assertTrue(index < index2, msg);
            index2 = msg.indexOf("ERROR");
            assertEquals(index2, -1, msg);
            foundError = true;
            break;
        }
        assertTrue(foundError);

        // this should throw an exception
        try {
            zmsImpl.getPolicy(mockDomRsrcCtx, "PolicyGetDom1", "Policy2");
            fail();
        } catch (Exception ex) {
            assertTrue(true);
        }

        zmsImpl.deleteTopLevelDomain(mockDomRsrcCtx, "PolicyGetDom1", auditRef);
    }

    @Test
    public void testGetPolicyThrowException() {
        String domainName = "PolicyGetDom1";
        String policyName = "Policy1";
        // Tests the getPolicy() condition : if (domain == null)...
        try {
            zms.getPolicy(mockDomRsrcCtx, domainName, policyName);
            fail("notfounderror not thrown.");
        } catch (ResourceException e) {
            assertEquals(e.getCode(), 404);
        }

        Mockito.when(mockDomRsrcCtx.getApiName()).thenReturn("posttopleveldomain").thenReturn("putpolicy");
        TopLevelDomain dom1 = createTopLevelDomainObject(domainName,
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);
        
        // Tests the getPolicy() condition: if (collection == null)...
        try {
            // Should fail b/c a policy was never added.
            zms.getPolicy(mockDomRsrcCtx, domainName, policyName);
            fail("notfounderror not thrown.");
        } catch (ResourceException e) {
            assertEquals(e.getCode(), 404);
        }

        // Tests the getPolicy() condition: if (policy == null)...
        try {
            String wrongPolicyName = "Policy2";

            Policy policy1 = createPolicyObject(domainName, policyName);
            zms.putPolicy(mockDomRsrcCtx, domainName, policyName, auditRef, policy1);

            // Should fail b/c trying to find a non-existent policy.
            zms.getPolicy(mockDomRsrcCtx, domainName, wrongPolicyName);
            fail("notfounderror not thrown.");
        } catch (ResourceException e) {
            assertEquals(e.getCode(), 404);
        }

        zms.deleteTopLevelDomain(mockDomRsrcCtx, domainName, auditRef);
    }

    @Test
    public void testPutPolicyThrowException() {
        String domainName = "DomainName";
        String policyName = "PolicyName";
        String wrongPolicyName = "WrongPolicyName";
        
        // Tests the putPolicy() condition : if (!policyResourceName(domainName, policyName).equals(policy.getName()))...
        try {
            Policy policy = createPolicyObject(domainName, wrongPolicyName);
            
            // policyName should not be the same as policy.getName()
            zms.putPolicy(mockDomRsrcCtx, domainName, policyName, auditRef, policy);
            fail("requesterror not thrown.");
        } catch (ResourceException e) {
            assertEquals(e.getCode(), 400);
        }
        
        // Tests the putPolicy() condition: if (domain == null)...
        try {
            Policy policy = createPolicyObject(domainName, policyName);
            
            // should fail b/c we never created a top level domain.
            zms.putPolicy(mockDomRsrcCtx, domainName, policyName, auditRef, policy);
            fail("requesterror not thrown.");
        } catch (ResourceException e) {
            assertEquals(e.getCode(), 404);
        }
    }

    @Test
    public void testCreatePolicy() {

        TopLevelDomain dom1 = createTopLevelDomainObject("PolicyAddDom1",
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        Policy policy1 = createPolicyObject("PolicyAddDom1", "Policy1");
        zms.putPolicy(mockDomRsrcCtx, "PolicyAddDom1", "Policy1", auditRef, policy1);

        Policy policyRes2 = zms.getPolicy(mockDomRsrcCtx, "PolicyAddDom1", "Policy1");
        assertNotNull(policyRes2);
        assertEquals(policyRes2.getName(), "PolicyAddDom1:policy.Policy1".toLowerCase());

        zms.deleteTopLevelDomain(mockDomRsrcCtx, "PolicyAddDom1", auditRef);
    }

    @Test
    public void testCreatePolicyCaseSensitive() {

        TopLevelDomain dom1 = createTopLevelDomainObject("PolicyAddDom1",
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        // Create policy with an "allow all" assertion
        Policy policy1 = createPolicyObject("PolicyAddDom1", "Policy1");
        // Make the assertions in the policy case sensitive with regards to action and resource
        policy1.setCaseSensitive(true);

        // Create assertion2
        Assertion assertion2 = new Assertion();
        assertion2.setAction("ReaD");
        assertion2.setEffect(AssertionEffect.ALLOW);
        assertion2.setResource("coretech:RESOURCE");
        assertion2.setRole("PolicyAddDom1:role.provider");

        // Create assertion3 which is identical to assertion2 but with a different case in action and resource
        Assertion assertion3 = new Assertion();
        assertion3.setAction("READ");
        assertion3.setEffect(AssertionEffect.ALLOW);
        assertion3.setResource("coretech:Resource");
        assertion3.setRole("PolicyAddDom1:role.provider");

        // Create assertion4 which is different than assertion 3 and 2
        Assertion assertion4 = new Assertion();
        assertion4.setAction("WritE");
        assertion4.setEffect(AssertionEffect.ALLOW);
        assertion4.setResource("coretech:OtherResource");
        assertion4.setRole("PolicyAddDom1:role.provider");

        policy1.getAssertions().add(assertion2);
        policy1.getAssertions().add(assertion3);
        policy1.getAssertions().add(assertion4);

        zms.putPolicy(mockDomRsrcCtx, "PolicyAddDom1", "Policy1", auditRef, policy1);

        Policy policyRes2 = zms.getPolicy(mockDomRsrcCtx, "PolicyAddDom1", "Policy1");
        assertNotNull(policyRes2);
        assertEquals(policyRes2.getName(), "PolicyAddDom1:policy.Policy1".toLowerCase());
        List<Assertion> assertions = policyRes2.getAssertions();
        assertEquals(assertions.size(), 3); // assertion 2 and 3 are considered identical so only one remained

        assertEquals(assertions.get(1).getAction(), "ReaD");
        assertEquals(assertions.get(1).getResource(), "coretech:RESOURCE");

        assertEquals(assertions.get(2).getAction(), "WritE");
        assertEquals(assertions.get(2).getResource(), "coretech:OtherResource");

        zms.deleteTopLevelDomain(mockDomRsrcCtx, "PolicyAddDom1", auditRef);
    }

    @Test
    public void testCreatePolicyWithLocalName() {

        TopLevelDomain dom1 = createTopLevelDomainObject("PolicyAddDom1",
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        Policy policy = new Policy();
        policy.setName("policy1");

        Assertion assertion = new Assertion();
        assertion.setAction("read");
        assertion.setEffect(AssertionEffect.ALLOW);
        assertion.setResource("policyadddom1:*");
        assertion.setRole("policyadddom1:role.admin");

        List<Assertion> assertList = new ArrayList<>();
        assertList.add(assertion);

        policy.setAssertions(assertList);

        zms.putPolicy(mockDomRsrcCtx, "PolicyAddDom1", "Policy1", auditRef, policy);

        Policy policyRes2 = zms.getPolicy(mockDomRsrcCtx, "PolicyAddDom1", "Policy1");
        assertNotNull(policyRes2);
        assertEquals(policyRes2.getName(), "PolicyAddDom1:policy.Policy1".toLowerCase());

        zms.deleteTopLevelDomain(mockDomRsrcCtx, "PolicyAddDom1", auditRef);
    }
    
    @Test
    public void testCreatePolicyMissingAuditRef() {
        String domain = "testCreatePolicyMissingAuditRef";
        TopLevelDomain dom = createTopLevelDomainObject(
                domain, "Test Domain1", "testOrg", adminUser);
        dom.setAuditEnabled(true);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom);

        Policy policy = createPolicyObject(domain, "Policy1");
        try {
            zms.putPolicy(mockDomRsrcCtx, domain, "Policy1", null, policy);
            fail("requesterror not thrown by putPolicy.");
        } catch (ResourceException ex) {
            assertEquals(400, ex.getCode());
            assertTrue(ex.getMessage().contains("Audit reference required"));
        } finally {
            zms.deleteTopLevelDomain(mockDomRsrcCtx, domain, auditRef);
        }
    }

    @Test
    public void testPutPolicyChanges() {
        String domain     = "PutPolicyChanges";
        String policyName = "Jobs";
        TopLevelDomain dom1 = createTopLevelDomainObject(
                domain, "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        Policy policy1 = createPolicyObject(domain, policyName);
        List<Assertion> origAsserts = policy1.getAssertions();

        String userId = "hank";
        
        Authority principalAuthority = new com.yahoo.athenz.common.server.debug.DebugPrincipalAuthority();
        String unsignedCreds = "v=U1;d=user;n=" + userId;
        Principal principal = SimplePrincipal.create("user", userId, unsignedCreds + ";s=signature",
                0, principalAuthority);
        assertNotNull(principal);
        ((SimplePrincipal) principal).setUnsignedCreds(unsignedCreds);
        
        ResourceContext rsrcCtx1 = createResourceContext(principal);
        zms.putPolicy(rsrcCtx1, domain, policyName, auditRef, policy1);

        Policy policyRes1A = zms.getPolicy(mockDomRsrcCtx, domain, policyName);
        List<Assertion> resAsserts = policyRes1A.getAssertions();

        // check assertions are the same - should only be 1
        assertEquals(origAsserts.size(), resAsserts.size());

        // now replace the old assertion with a new ones
        //
        Assertion assertionA = new Assertion();
        assertionA.setResource(domain + ":books");
        assertionA.setAction("READ");
        assertionA.setRole(domain + ":role.librarian");
        assertionA.setEffect(AssertionEffect.ALLOW);

        Assertion assertionB = new Assertion();
        assertionB.setResource(domain + ":jupiter");
        assertionB.setAction("TRAVEL");
        assertionB.setRole(domain + ":role.astronaut");
        assertionB.setEffect(AssertionEffect.ALLOW);

        List<Assertion> newAssertions = new ArrayList<>();
        newAssertions.add(assertionA);
        newAssertions.add(assertionB);

        policyRes1A.setAssertions(newAssertions);
        
        zms.putPolicy(mockDomRsrcCtx, domain, policyName, auditRef, policyRes1A);

        Policy policyRes1B = zms.getPolicy(mockDomRsrcCtx, domain, policyName);
        List<Assertion> resAssertsB = policyRes1B.getAssertions();

        // check assertions are the same - should be 2
        assertEquals(newAssertions.size(), resAssertsB.size());

        zms.deleteTopLevelDomain(mockDomRsrcCtx, domain, auditRef);
    }

    @Test
    public void testPutAdminPolicyRejection() {
        
        String domain = "put-admin-rejection";

        TopLevelDomain dom1 = createTopLevelDomainObject(
                domain, "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        Policy policy = createPolicyObject(domain, "admin");
        try {
            zms.putPolicy(mockDomRsrcCtx, domain, "admin", auditRef, policy);
            fail();
        } catch (ResourceException ex) {
            assertEquals(400, ex.getCode());
            assertTrue(ex.getMessage().contains("admin policy cannot be modified"), ex.getMessage());
        }
        zms.deleteTopLevelDomain(mockDomRsrcCtx, domain, auditRef);
    }
    
    @Test
    public void testCreatePolicyNoAssertions() {

        TopLevelDomain dom1 = createTopLevelDomainObject(
                "testCreatePolicyNoAssertions", "Test Domain1", "testOrg",
                adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        Policy policy1 = new Policy();
        policy1.setName(ZMSUtils.policyResourceName("testCreatePolicyNoAssertions",
                "Policy1"));

        try {
            zms.putPolicy(mockDomRsrcCtx, "testCreatePolicyNoAssertions", "Policy1", auditRef, policy1);
            fail();
        } catch (ResourceException ex) {
            assertEquals(400, ex.getCode());
        }

        zms.deleteTopLevelDomain(mockDomRsrcCtx, "testCreatePolicyNoAssertions", auditRef);
    }

    @Test
    public void testPutPolicyInvalidAssertionResources() {
        
        String domainName = "InvalidAssertionResources";
        String policyName = "Policy1";
        
        TopLevelDomain dom1 = createTopLevelDomainObject(
                domainName, "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        Policy policy = new Policy();
        policy.setName(ZMSUtils.policyResourceName(domainName, policyName));

        // assertion missing domain name
        
        Assertion assertion = new Assertion();
        assertion.setAction("update");
        assertion.setEffect(AssertionEffect.ALLOW);
        assertion.setResource("resource1");
        assertion.setRole(ZMSUtils.roleResourceName(domainName, "role1"));

        List<Assertion> assertList = new ArrayList<>();
        assertList.add(assertion);
        policy.setAssertions(assertList);
        
        try {
            zms.putPolicy(mockDomRsrcCtx, domainName, policyName, auditRef, policy);
            fail();
        } catch (ResourceException ex) {
            assertEquals(400, ex.getCode());
        }
        
        // assertion with invalid domain name
        
        assertion = new Assertion();
        assertion.setAction("update");
        assertion.setEffect(AssertionEffect.ALLOW);
        assertion.setResource("domain name:resource1");
        assertion.setRole(ZMSUtils.roleResourceName(domainName, "role1"));
        
        assertList.clear();
        assertList.add(assertion);
        policy.setAssertions(assertList);
        
        try {
            zms.putPolicy(mockDomRsrcCtx, domainName, policyName, auditRef, policy);
            fail();
        } catch (ResourceException ex) {
            assertEquals(400, ex.getCode());
        }

        zms.deleteTopLevelDomain(mockDomRsrcCtx, domainName, auditRef);
    }
    
    @Test
    public void testCreatePolicyMismatchName() {

        TopLevelDomain dom1 = createTopLevelDomainObject(
                "PolicyAddMismatchNameDom1", "Test Domain1", "testOrg",
                adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        Policy policy1 = createPolicyObject("PolicyAddMismatchNameDom1",
                "Policy1");

        try {
            zms.putPolicy(mockDomRsrcCtx, "PolicyAddMismatchNameDom1",
                    "PolicyAddMismatchNameDom1.Policy1", auditRef, policy1);
            fail();
        } catch (ResourceException ex) {
            assertEquals(400, ex.getCode());
        }

        zms.deleteTopLevelDomain(mockDomRsrcCtx, "PolicyAddMismatchNameDom1", auditRef);
    }

    @Test
    public void testCreatePolicyInvalidName() {

        TopLevelDomain dom1 = createTopLevelDomainObject(
                "PolicyAddInvalidNameDom1", "Test Domain1", "testOrg",
                adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        Policy policy = new Policy();
        policy.setName("Policy1");

        try {
            zms.putPolicy(mockDomRsrcCtx, "PolicyAddInvalidNameDom1", "Policy1", auditRef, policy);
            fail();
        } catch (ResourceException ex) {
            assertEquals(400, ex.getCode());
        }

        zms.deleteTopLevelDomain(mockDomRsrcCtx, "PolicyAddInvalidNameDom1", auditRef);
    }

    @Test
    public void testCreatePolicyInvalidStruct() {

        TopLevelDomain dom1 = createTopLevelDomainObject(
                "PolicyAddInvalidStructDom1", "Test Domain1", "testOrg",
                adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        Policy policy = new Policy();

        try {
            zms.putPolicy(mockDomRsrcCtx, "PolicyAddInvalidStructDom1", "Policy1", auditRef, policy);
            fail();
        } catch (ResourceException ex) {
            assertEquals(400, ex.getCode());
        }

        zms.deleteTopLevelDomain(mockDomRsrcCtx, "PolicyAddInvalidStructDom1", auditRef);
    }

    @Test
    public void testDeletePolicy() {

        TopLevelDomain dom1 = createTopLevelDomainObject("PolicyDelDom1",
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        Policy policy1 = createPolicyObject("PolicyDelDom1", "Policy1");
        zms.putPolicy(mockDomRsrcCtx, "PolicyDelDom1", "Policy1", auditRef, policy1);

        Policy policy2 = createPolicyObject("PolicyDelDom1", "Policy2");
        zms.putPolicy(mockDomRsrcCtx, "PolicyDelDom1", "Policy2", auditRef, policy2);

        Policy policyRes1 = zms.getPolicy(mockDomRsrcCtx, "PolicyDelDom1", "Policy1");
        assertNotNull(policyRes1);

        Policy policyRes2 = zms.getPolicy(mockDomRsrcCtx, "PolicyDelDom1", "Policy2");
        assertNotNull(policyRes2);

        zms.deletePolicy(mockDomRsrcCtx, "PolicyDelDom1", "Policy1", auditRef);

        // we need to get an exception here
        try {
            zms.getPolicy(mockDomRsrcCtx, "PolicyDelDom1", "Policy1");
            fail();
        } catch (Exception ex) {
            assertTrue(true);
        }

        policyRes2 = zms.getPolicy(mockDomRsrcCtx, "PolicyDelDom1", "Policy2");
        assertNotNull(policyRes2);

        zms.deletePolicy(mockDomRsrcCtx, "PolicyDelDom1", "Policy2", auditRef);

        // we need to get an exception here
        try {
            zms.getPolicy(mockDomRsrcCtx, "PolicyDelDom1", "Policy1");
            fail();
        } catch (Exception ex) {
            assertTrue(true);
        }

        // we need to get an exception here
        try {
            zms.getPolicy(mockDomRsrcCtx, "PolicyDelDom1", "Policy2");
            fail();
        } catch (Exception ex) {
            assertTrue(true);
        }

        zms.deleteTopLevelDomain(mockDomRsrcCtx, "PolicyDelDom1", auditRef);
    }
    
    @Test
    public void testDeletePolicyThrowException() {

        TestAuditLogger alogger = new TestAuditLogger();
        ZMSImpl zmsImpl = getZmsImpl(alogger);

        String domainName = "WrongDomainName";
        String policyName = "WrongPolicyName";
        try {
            zmsImpl.deletePolicy(mockDomRsrcCtx, domainName, policyName, auditRef);
            fail("requesterror not thrown.");
        } catch (ResourceException e) {
            assertEquals(e.getCode(), 404);
        }
    }

    @Test
    public void testDeleteAdminPolicy() {

        TopLevelDomain dom1 = createTopLevelDomainObject("PolicyAdminDelDom1",
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        try {
            zms.deletePolicy(mockDomRsrcCtx, "PolicyAdminDelDom1", "admin", auditRef);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 400);
        }

        zms.deleteTopLevelDomain(mockDomRsrcCtx, "PolicyAdminDelDom1", auditRef);
    }

    @Test
    public void testDeletePolicyMissingAuditRef() {
        // create a new policy without an auditref
        String domain = "testDeletePolicyMissingAuditRef";
        TopLevelDomain dom = createTopLevelDomainObject(
            domain, null, null, adminUser);
        dom.setAuditEnabled(true);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom);

        try {
            zms.deletePolicy(mockDomRsrcCtx, domain, "Policy1", null);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 400);
            assertTrue(ex.getMessage().contains("Audit reference required"));
        } finally {
            zms.deleteTopLevelDomain(mockDomRsrcCtx, domain, auditRef);
        }
    }

    @Test
    public void testCreateServiceIdentity() {

        TopLevelDomain dom1 = createTopLevelDomainObject("ServiceAddDom1",
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        ServiceIdentity service = createServiceObject("ServiceAddDom1",
                "Service1", "http://localhost", "/usr/bin/java", "root",
                "users", "host1");

        zms.putServiceIdentity(mockDomRsrcCtx, "ServiceAddDom1", "Service1", auditRef, service);

        ServiceIdentity serviceRes2 = zms.getServiceIdentity(mockDomRsrcCtx, "ServiceAddDom1",
                "Service1");
        assertNotNull(serviceRes2);
        assertEquals(serviceRes2.getName(), "ServiceAddDom1.Service1".toLowerCase());

        zms.deleteTopLevelDomain(mockDomRsrcCtx, "ServiceAddDom1", auditRef);
    }

    @Test
    public void testCreateServiceIdentityNotSimpleName() {

        TestAuditLogger alogger = new TestAuditLogger();
        ZMSImpl zmsImpl = getZmsImpl(alogger);

        TopLevelDomain dom1 = createTopLevelDomainObject("ServiceAddDom1NotSimpleName",
                "Test Domain1", "testOrg", adminUser);
        zmsImpl.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        ServiceIdentity service = createServiceObject("ServiceAddDom1NotSimpleName",
                "Service1.Test", "http://localhost", "/usr/bin/java", "root",
                "users", "host1");

        try {
            zmsImpl.putServiceIdentity(mockDomRsrcCtx, "ServiceAddDom1NotSimpleName", "Service1.Test", auditRef, service);
            fail();
        } catch (ResourceException ex) {
            assertEquals(400, ex.getCode());
        }

        zmsImpl.deleteTopLevelDomain(mockDomRsrcCtx, "ServiceAddDom1NotSimpleName", auditRef);
    }
    
    @Test
    public void testCreateServiceIdentityMissingAuditRef() {
        String domain = "testCreateServiceIdentityMissingAuditRef";
        TopLevelDomain dom = createTopLevelDomainObject(
                domain, "Test Domain1", "testOrg", adminUser);
        dom.setAuditEnabled(true);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom);

        ServiceIdentity service = createServiceObject(
            domain,
            "Service1", "http://localhost", "/usr/bin/java", "root",
            "users", "host1");
        try {
            zms.putServiceIdentity(mockDomRsrcCtx, domain, "Service1", null, service);
            fail("requesterror not thrown by putServiceIdentity.");
        } catch (ResourceException ex) {
            assertEquals(400, ex.getCode());
            assertTrue(ex.getMessage().contains("Audit reference required"));
        } finally {
            zms.deleteTopLevelDomain(mockDomRsrcCtx, domain, auditRef);
        }
    }

    @Test
    public void testCreateServiceIdentityMismatchName() {

        TopLevelDomain dom1 = createTopLevelDomainObject("ServiceAddMismatchNameDom1",
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        ServiceIdentity service = createServiceObject("ServiceAddMismatchNameDom1",
                "Service1", "http://localhost", "/usr/bin/java", "root",
                "users", "host1");

        try {
            zms.putServiceIdentity(mockDomRsrcCtx, "ServiceAddMismatchNameDom1", 
                    "ServiceAddMismatchNameDom1.Service1", auditRef, service);
            fail();
        } catch (ResourceException ex) {
            assertEquals(400, ex.getCode());
        }

        zms.deleteTopLevelDomain(mockDomRsrcCtx, "ServiceAddMismatchNameDom1", auditRef);
    }

    @Test
    public void testCreateServiceIdentityInvalidName() {

        TopLevelDomain dom1 = createTopLevelDomainObject("ServiceAddInvalidNameDom1",
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        ServiceIdentity service = new ServiceIdentity();
        service.setName("Service1");

        try {
            zms.putServiceIdentity(mockDomRsrcCtx, "ServiceAddInvalidNameDom1", "Service1", auditRef, service);
            fail();
        } catch (ResourceException ex) {
            assertEquals(400, ex.getCode());
        }

        zms.deleteTopLevelDomain(mockDomRsrcCtx, "ServiceAddInvalidNameDom1", auditRef);
    }

    @Test
    public void testCreateServiceIdentityInvalidCert() {

        TopLevelDomain dom1 = createTopLevelDomainObject("ServiceAddInvalidCertDom1",
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        ServiceIdentity service = new ServiceIdentity();
        service.setName(ZMSUtils.serviceResourceName("ServiceAddInvalidCertDom1", "Service1"));
        List<PublicKeyEntry> pubKeys = new ArrayList<>();
        pubKeys.add(new PublicKeyEntry().setId("0").setKey("LS0tLS1CRUdJTiBQVUJMSUMgS0VZLS0tLS0KTUlHZk1BMEdDU3FHU0liM0RRRUJBUVVBQTRHTk"));
        service.setPublicKeys(pubKeys);
        
        try {
            zms.putServiceIdentity(mockDomRsrcCtx, "ServiceAddInvalidCertDom1", "Service1", auditRef, service);
            fail();
        } catch (ResourceException ex) {
            assertEquals(400, ex.getCode());
        }

        zms.deleteTopLevelDomain(mockDomRsrcCtx, "ServiceAddInvalidCertDom1", auditRef);
    }

    @Test
    public void testCreateServiceIdentityInvalidStruct() {

        TopLevelDomain dom1 = createTopLevelDomainObject("ServiceAddInvalidStructDom1",
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        ServiceIdentity service = new ServiceIdentity();
        
        try {
            zms.putServiceIdentity(mockDomRsrcCtx, "ServiceAddInvalidStructDom1", "Service1", auditRef, service);
            fail();
        } catch (ResourceException ex) {
            assertEquals(400, ex.getCode());
        }

        zms.deleteTopLevelDomain(mockDomRsrcCtx, "ServiceAddInvalidStructDom1", auditRef);
    }


    @Test
    public void testPutServiceIdentityWithoutPubKey() {
        String domainName = "ServicePutDom1";
        String serviceName = "Service1";

        TopLevelDomain dom1 = createTopLevelDomainObject(domainName, "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        ServiceIdentity service = new ServiceIdentity();
        service.setName(ZMSUtils.serviceResourceName(domainName, serviceName));

        zms.putServiceIdentity(mockDomRsrcCtx, domainName, serviceName, auditRef, service);

        ServiceIdentity serviceRes = zms.getServiceIdentity(mockDomRsrcCtx, domainName, serviceName);
        assertNotNull(serviceRes);
        assertEquals(serviceRes.getName(), "ServicePutDom1.Service1".toLowerCase());

        zms.deleteTopLevelDomain(mockDomRsrcCtx,  domainName, auditRef);
    }

    @Test
    public void testPutServiceIdentityInvalidServiceName() {
        String domainName = "ServicePutDom1";
        String serviceName = "cloud";

        TopLevelDomain dom1 = createTopLevelDomainObject(domainName, "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        ServiceIdentity service = new ServiceIdentity();
        service.setName(ZMSUtils.serviceResourceName(domainName, serviceName));

        try {
            zms.putServiceIdentity(mockDomRsrcCtx, domainName, serviceName, auditRef, service);
            fail();
        } catch (ResourceException ex) {
            assertTrue(ex.getMessage().contains("Invalid/Reserved service name"));
        }

        zms.deleteTopLevelDomain(mockDomRsrcCtx,  domainName, auditRef);
    }

    @Test
    public void testPutServiceIdentityInvalidEndPoint() {
        String domainName = "ServicePutDom1";
        String serviceName = "Service1";

        TopLevelDomain dom1 = createTopLevelDomainObject(domainName, "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        ServiceIdentity service = new ServiceIdentity();
        service.setName(ZMSUtils.serviceResourceName(domainName, serviceName));
        service.setProviderEndpoint("https://sometestcompany.com");

        try {
            zms.putServiceIdentity(mockDomRsrcCtx, domainName, serviceName, auditRef, service);
            fail();
        } catch (ResourceException ex) {
            assertTrue(ex.getMessage().contains("Invalid endpoint"));
        }

        zms.deleteTopLevelDomain(mockDomRsrcCtx,  domainName, auditRef);
    }

    @Test
    public void testPutServiceIdentityThrowException() {
        String domainName = "DomainName";
        String serviceName = "ServiceName";
        String wrongServiceName = "WrongServiceName";
        
        // Tests the putServiceIdentity() condition: if (!serviceResourceName(domainName, serviceName).equals(detail.getName()))...
        try {
            ServiceIdentity detail = createServiceObject(domainName,
                    wrongServiceName, "http://localhost", "/usr/bin/java", "root",
                    "users", "host1");
            
            // serviceName should not rendered to be the same as domainName:service.wrongServiceName
            zms.putServiceIdentity(mockDomRsrcCtx, domainName, serviceName, auditRef, detail);
            fail("requesterror not thrown.");
        } catch (ResourceException e) {
            assertEquals(e.getCode(), 400);
        }
        
        // Tests the putServiceIdentity() condition: if (domain == null)...
        try {
            ServiceIdentity detail = createServiceObject(domainName,
                    serviceName, "http://localhost", "/usr/bin/java", "root",
                    "users", "host1");
            
            // should fail b/c we never created a top level domain.
            zms.putServiceIdentity(mockDomRsrcCtx, domainName, serviceName, auditRef, detail);
            fail("notfounderror not thrown.");
        } catch (ResourceException e) {
            assertEquals(e.getCode(), 404);
        }
    }

    @Test
    public void testGetServiceIdentity() {

        TopLevelDomain dom1 = createTopLevelDomainObject("ServiceGetDom1",
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        ServiceIdentity service = createServiceObject("ServiceGetDom1",
                "Service1", "http://localhost", "/usr/bin/java", "root",
                "users", "host1");

        zms.putServiceIdentity(mockDomRsrcCtx, "ServiceGetDom1", "Service1", auditRef, service);

        ServiceIdentity serviceRes = zms.getServiceIdentity(mockDomRsrcCtx, "ServiceGetDom1",
                "Service1");
        assertNotNull(serviceRes);
        assertEquals(serviceRes.getName(), "ServiceGetDom1.Service1".toLowerCase());
        assertEquals(serviceRes.getExecutable(), "/usr/bin/java");
        assertEquals(serviceRes.getGroup(), "users");
        assertEquals(serviceRes.getUser(), "root");

        // provider endpoint is a system meta attribute so we shouldn't saved it
        assertNull(serviceRes.getProviderEndpoint());

        List<String> hosts = serviceRes.getHosts();
        assertNotNull(hosts);
        assertEquals(hosts.size(), 1);
        assertEquals(hosts.get(0), "host1");

        // this should throw a not found exception
        try {
            zms.getServiceIdentity(mockDomRsrcCtx, "ServiceGetDom1", "Service2");
            fail();
        } catch (ResourceException ex) {
            assertEquals(404, ex.getCode());
        }

        // this should throw a request error exception
        try {
            zms.getServiceIdentity(mockDomRsrcCtx, "ServiceGetDom1", "Service2.Service3");
            fail();
        } catch (ResourceException ex) {
            assertEquals(400, ex.getCode());
        }
        
        zms.deleteTopLevelDomain(mockDomRsrcCtx, "ServiceGetDom1", auditRef);
    }

    @Test
    public void testPutServiceIdentitySystemMeta() {

        final String domainName = "service-system-meta";
        final String serviceName = "service1";
        TopLevelDomain dom1 = createTopLevelDomainObject(domainName,
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        ServiceIdentity service = createServiceObject(domainName,
                serviceName, "http://localhost", "/usr/bin/java", "root",
                "users", "host1");

        zms.putServiceIdentity(mockDomRsrcCtx, domainName, serviceName, auditRef, service);

        ServiceIdentity serviceRes = zms.getServiceIdentity(mockDomRsrcCtx, domainName, serviceName);
        assertNotNull(serviceRes);
        assertEquals(serviceRes.getName(), domainName + "." + serviceName);
        assertEquals(serviceRes.getExecutable(), "/usr/bin/java");
        assertEquals(serviceRes.getGroup(), "users");
        assertEquals(serviceRes.getUser(), "root");

        // provider endpoint is a system meta attribute so we shouldn't saved it
        assertNull(serviceRes.getProviderEndpoint());

        // now let's set the meta attribute

        ServiceIdentitySystemMeta meta = new ServiceIdentitySystemMeta();
        zms.putServiceIdentitySystemMeta(mockDomRsrcCtx, domainName, serviceName, "providerendpoint", auditRef, meta);

        // we expect no changes

        serviceRes = zms.getServiceIdentity(mockDomRsrcCtx, domainName, serviceName);
        assertEquals(serviceRes.getName(), domainName + "." + serviceName);
        assertEquals(serviceRes.getExecutable(), "/usr/bin/java");
        assertEquals(serviceRes.getGroup(), "users");
        assertEquals(serviceRes.getUser(), "root");
        assertNull(serviceRes.getProviderEndpoint());

        // now let's change the endpoint

        meta.setProviderEndpoint("https://localhost");
        zms.putServiceIdentitySystemMeta(mockDomRsrcCtx, domainName, serviceName, "providerendpoint", auditRef, meta);

        serviceRes = zms.getServiceIdentity(mockDomRsrcCtx, domainName, serviceName);
        assertEquals(serviceRes.getName(), domainName + "." + serviceName);
        assertEquals(serviceRes.getExecutable(), "/usr/bin/java");
        assertEquals(serviceRes.getGroup(), "users");
        assertEquals(serviceRes.getUser(), "root");
        assertEquals(serviceRes.getProviderEndpoint(), "https://localhost");

        zms.deleteTopLevelDomain(mockDomRsrcCtx, domainName, auditRef);
    }

    @Test
    public void testGetServiceIdentityThrowException() {
        String domainName = "ServiceGetDom1";
        String serviceName = "Service1";
        
        // Tests the getServiceIdentity() condition : if (domain == null)...
        try {
            // Should fail because we never created this domain.
            zms.getServiceIdentity(mockDomRsrcCtx, domainName, serviceName);
            fail("notfounderror not thrown.");
        } catch (ResourceException e) {
            assertEquals(e.getCode(), 404);
        }
        
        TopLevelDomain dom1 = createTopLevelDomainObject(domainName,
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);
        
        // Tests the getServiceIdentity() condition : if (collection == null)...
        try {
            // Should fail because we never added a service identity to this domain.
            zms.getServiceIdentity(mockDomRsrcCtx, domainName, serviceName);
            fail("notfounderror not thrown.");
        } catch (ResourceException e) {
            assertEquals(e.getCode(), 404);
        }
        
        // Tests the getServiceIdentity() condition : if (service == null)...
        try {
            String wrongServiceName = "Service2";
            
            ServiceIdentity service = createServiceObject(domainName,
                    serviceName, "http://localhost", "/usr/bin/java", "root",
                    "users", "host1");
            zms.putServiceIdentity(mockDomRsrcCtx, domainName, serviceName, auditRef, service);

            // Should fail because trying to find a non-existent service identity.
            zms.getServiceIdentity(mockDomRsrcCtx, domainName, wrongServiceName);
            fail("notfounderror not thrown.");
        } catch (ResourceException e) {
            assertEquals(e.getCode(), 404);
        }
        
        zms.deleteTopLevelDomain(mockDomRsrcCtx, domainName, auditRef);
    }

    @Test
    public void testDeleteServiceIdentity() {

        TopLevelDomain dom1 = createTopLevelDomainObject("ServiceDelDom1",
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        ServiceIdentity service1 = createServiceObject("ServiceDelDom1",
                "Service1", "http://localhost", "/usr/bin/java", "root",
                "users", "host1");

        zms.putServiceIdentity(mockDomRsrcCtx, "ServiceDelDom1", "Service1", auditRef, service1);

        ServiceIdentity service2 = createServiceObject("ServiceDelDom1",
                "Service2", "http://localhost", "/usr/bin/java", "root",
                "users", "host1");

        zms.putServiceIdentity(mockDomRsrcCtx, "ServiceDelDom1", "Service2", auditRef, service2);

        ServiceIdentity serviceRes1 = zms.getServiceIdentity(mockDomRsrcCtx, "ServiceDelDom1",
                "Service1");
        assertNotNull(serviceRes1);

        ServiceIdentity serviceRes2 = zms.getServiceIdentity(mockDomRsrcCtx, "ServiceDelDom1",
                "Service2");
        assertNotNull(serviceRes2);

        zms.deleteServiceIdentity(mockDomRsrcCtx, "ServiceDelDom1", "Service1", auditRef);

        // this should throw a not found exception
        try {
            zms.getServiceIdentity(mockDomRsrcCtx, "ServiceDelDom1", "Service1");
            fail();
        } catch (ResourceException ex) {
            assertEquals(404, ex.getCode());
        }

        serviceRes2 = zms.getServiceIdentity(mockDomRsrcCtx, "ServiceDelDom1", "Service2");
        assertNotNull(serviceRes2);

        zms.deleteServiceIdentity(mockDomRsrcCtx, "ServiceDelDom1", "Service2", auditRef);

        // this should throw a not found exception
        try {
            zms.getServiceIdentity(mockDomRsrcCtx, "ServiceDelDom1", "Service1");
            fail();
        } catch (ResourceException ex) {
            assertEquals(404, ex.getCode());
        }

        // this should throw a not found exception
        try {
            zms.getServiceIdentity(mockDomRsrcCtx, "ServiceDelDom1", "Service2");
            fail();
        } catch (ResourceException ex) {
            assertEquals(404, ex.getCode());
        }

        // this should throw an invalid exception
        try {
            zms.getServiceIdentity(mockDomRsrcCtx, "ServiceDelDom1", "Service2.Service3");
            fail();
        } catch (ResourceException ex) {
            assertEquals(400, ex.getCode());
        }
        
        zms.deleteTopLevelDomain(mockDomRsrcCtx, "ServiceDelDom1", auditRef);
    }

    @Test
    public void testDeleteServiceIdentityMissingAuditRef() {
        String domain = "testDeleteServiceIdentityMissingAuditRef";
        TopLevelDomain dom = createTopLevelDomainObject(
                domain, "Test Domain1", "testOrg", adminUser);
        dom.setAuditEnabled(true);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom);

        ServiceIdentity service = createServiceObject(
            domain,
            "Service1", "http://localhost", "/usr/bin/java", "root",
            "users", "host1");
        zms.putServiceIdentity(mockDomRsrcCtx, domain, "Service1", auditRef, service);
        ServiceIdentity serviceRes =
            zms.getServiceIdentity(mockDomRsrcCtx, domain, "Service1");
        assertNotNull(serviceRes);
        try {
            zms.deleteServiceIdentity(mockDomRsrcCtx, domain, "Service1", null);
            fail("requesterror not thrown by deleteServiceIdentity.");
        } catch (ResourceException ex) {
            assertEquals(400, ex.getCode());
            assertTrue(ex.getMessage().contains("Audit reference required"));
        } finally {
            zms.deleteTopLevelDomain(mockDomRsrcCtx, domain, auditRef);
        }
    }

    @Test
    public void testDeleteServiceIdentityThrowException() {

        TestAuditLogger alogger = new TestAuditLogger();
        ZMSImpl zmsImpl = getZmsImpl(alogger);

        String domainName = "WrongDomainName";
        String serviceName = "WrongServiceName";
        try {
            zmsImpl.deleteServiceIdentity(mockDomRsrcCtx, domainName, serviceName, auditRef);
            fail("notfounderror not thrown.");
        } catch (ResourceException e) {
            assertEquals(e.getCode(), 404);
        }
    }

    @Test
    public void testGetServiceIdentityList() {

        TopLevelDomain dom1 = createTopLevelDomainObject("ServiceListDom1",
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        ServiceIdentity service1 = createServiceObject("ServiceListDom1",
                "Service1", "http://localhost", "/usr/bin/java", "root",
                "users", "host1");

        zms.putServiceIdentity(mockDomRsrcCtx, "ServiceListDom1", "Service1", auditRef, service1);

        ServiceIdentity service2 = createServiceObject("ServiceListDom1",
                "Service2", "http://localhost", "/usr/bin/java", "root",
                "users", "host1");

        zms.putServiceIdentity(mockDomRsrcCtx, "ServiceListDom1", "Service2", auditRef, service2);

        ServiceIdentityList serviceList = zms.getServiceIdentityList(
                mockDomRsrcCtx, "ServiceListDom1", null, null);
        assertNotNull(serviceList);
        assertEquals(serviceList.getNames().size(), 2);

        assertTrue(serviceList.getNames().contains("Service1".toLowerCase()));
        assertTrue(serviceList.getNames().contains("Service2".toLowerCase()));

        zms.deleteTopLevelDomain(mockDomRsrcCtx, "ServiceListDom1", auditRef);
    }

    @Test
    public void testGetServiceIdentityListParams() {

        TopLevelDomain dom1 = createTopLevelDomainObject(
                "ServiceListParamsDom1", "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        ServiceIdentity service1 = createServiceObject("ServiceListParamsDom1",
                "Service1", "http://localhost", "/usr/bin/java", "root",
                "users", "host1");

        zms.putServiceIdentity(mockDomRsrcCtx, "ServiceListParamsDom1", "Service1", auditRef, service1);

        ServiceIdentity service2 = createServiceObject("ServiceListParamsDom1",
                "Service2", "http://localhost", "/usr/bin/java", "root",
                "users", "host1");

        zms.putServiceIdentity(mockDomRsrcCtx, "ServiceListParamsDom1", "Service2", auditRef, service2);

        ServiceIdentityList serviceList = zms.getServiceIdentityList(
                mockDomRsrcCtx, "ServiceListParamsDom1", 1, null);
        assertNotNull(serviceList);
        assertEquals(serviceList.getNames().size(), 1);

        serviceList = zms.getServiceIdentityList(mockDomRsrcCtx, "ServiceListParamsDom1", null,
                "Service1");
        assertNotNull(serviceList);
        assertEquals(serviceList.getNames().size(), 1);

        assertFalse(serviceList.getNames().contains("Service1".toLowerCase()));
        assertTrue(serviceList.getNames().contains("Service2".toLowerCase()));

        zms.deleteTopLevelDomain(mockDomRsrcCtx, "ServiceListParamsDom1", auditRef);
    }
    
    @Test
    public void testGetServiceIdentityListThrowException() {
        String domainName = "WrongDomainName";
        try {
            zms.getServiceIdentityList(mockDomRsrcCtx, domainName, null, null);
            fail("notfounderror not thrown.");
        } catch (ResourceException e) {
            assertEquals(e.getCode(), 404);
        }
    }

    @Test
    public void testGetEntity() {

        TopLevelDomain dom1 = createTopLevelDomainObject("GetEntityDom1",
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        Entity entity1 = createEntityObject("Entity1");
        zms.putEntity(mockDomRsrcCtx, "GetEntityDom1", "Entity1", auditRef, entity1);

        Entity entity2 = zms.getEntity(mockDomRsrcCtx, "GetEntityDom1", "Entity1");
        assertNotNull(entity2);

        assertEquals(entity2.getName(), "Entity1".toLowerCase());
        zms.deleteTopLevelDomain(mockDomRsrcCtx, "GetEntityDom1", auditRef);
    }
    
    @Test
    public void testGetEntityThrowException() {
        try {
            zms.getEntity(mockDomRsrcCtx, "wrongDomainName", "wrongEntityName");
            fail("notfounderror not thrown.");
        } catch (ResourceException e) {
            assertEquals(e.getCode(), 404);
        }
    }

    @Test
    public void testCreateEntity() {

        TopLevelDomain dom1 = createTopLevelDomainObject("CreateEntityDom1",
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        Entity entity1 = createEntityObject("Entity1");
        zms.putEntity(mockDomRsrcCtx, "CreateEntityDom1", "Entity1", auditRef, entity1);

        Entity entity2 = zms.getEntity(mockDomRsrcCtx, "CreateEntityDom1", "Entity1");
        assertNotNull(entity2);
        assertEquals(entity2.getName(), "Entity1".toLowerCase());

        zms.deleteTopLevelDomain(mockDomRsrcCtx, "CreateEntityDom1", auditRef);
    }

    @Test
    public void testListEntity() {

        TopLevelDomain dom1 = createTopLevelDomainObject("ListEntityDom1",
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        EntityList entityList = zms.getEntityList(mockDomRsrcCtx, "ListEntityDom1");
        assertNotNull(entityList);
        assertEquals(0, entityList.getNames().size());
        
        Entity entity1 = createEntityObject("Entity1");
        zms.putEntity(mockDomRsrcCtx, "ListEntityDom1", "Entity1", auditRef, entity1);

        entityList = zms.getEntityList(mockDomRsrcCtx, "ListEntityDom1");
        assertNotNull(entityList);
        assertEquals(1, entityList.getNames().size());
        assertTrue(entityList.getNames().contains("entity1"));

        Entity entity2 = createEntityObject("Entity2");
        zms.putEntity(mockDomRsrcCtx, "ListEntityDom1", "Entity2", auditRef, entity2);

        entityList = zms.getEntityList(mockDomRsrcCtx, "ListEntityDom1");
        assertNotNull(entityList);
        assertEquals(2, entityList.getNames().size());
        assertTrue(entityList.getNames().contains("entity1"));
        assertTrue(entityList.getNames().contains("entity2"));

        zms.deleteEntity(mockDomRsrcCtx, "ListEntityDom1", "entity1", auditRef);
        
        entityList = zms.getEntityList(mockDomRsrcCtx, "ListEntityDom1");
        assertNotNull(entityList);
        assertEquals(1, entityList.getNames().size());
        assertTrue(entityList.getNames().contains("entity2"));
        
        zms.deleteEntity(mockDomRsrcCtx, "ListEntityDom1", "entity2", auditRef);

        entityList = zms.getEntityList(mockDomRsrcCtx, "ListEntityDom1");
        assertNotNull(entityList);
        assertEquals(0, entityList.getNames().size());
        
        zms.deleteTopLevelDomain(mockDomRsrcCtx, "ListEntityDom1", auditRef);
    }
    
    @Test
    public void testCreateEntityMissingAuditRef() {
        String domain = "testCreateEntityMissingAuditRef";
        TopLevelDomain dom = createTopLevelDomainObject(
                domain, "Test Domain1", "testOrg", adminUser);
        dom.setAuditEnabled(true);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom);

        Entity entity = createEntityObject("Entity1");
        try {
            zms.putEntity(mockDomRsrcCtx, domain, "Entity1", null, entity);
            fail("requesterror not thrown by putEntity.");
        } catch (ResourceException ex) {
            assertEquals(400, ex.getCode());
            assertTrue(ex.getMessage().contains("Audit reference required"));
        } finally {
            zms.deleteTopLevelDomain(mockDomRsrcCtx, domain, auditRef);
        }
    }

    @Test
    public void testDeleteEntity() {

        TopLevelDomain dom1 = createTopLevelDomainObject("DelEntityDom1",
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        Entity entity1 = createEntityObject("Entity1");
        zms.putEntity(mockDomRsrcCtx, "DelEntityDom1", "Entity1", auditRef, entity1);

        Entity entity2 = createEntityObject("Entity2");
        zms.putEntity(mockDomRsrcCtx, "DelEntityDom1", "Entity2", auditRef, entity2);

        Entity entityRes = zms.getEntity(mockDomRsrcCtx, "DelEntityDom1", "Entity1");
        assertNotNull(entityRes);

        entityRes = zms.getEntity(mockDomRsrcCtx, "DelEntityDom1", "Entity2");
        assertNotNull(entityRes);

        zms.deleteEntity(mockDomRsrcCtx, "DelEntityDom1", "Entity1", auditRef);

        try {
            zms.getEntity(mockDomRsrcCtx, "DelEntityDom1", "Entity1");
            fail();
        } catch (Exception ex) {
            assertTrue(true);
        }

        entityRes = zms.getEntity(mockDomRsrcCtx, "DelEntityDom1", "Entity2");
        assertNotNull(entityRes);

        zms.deleteEntity(mockDomRsrcCtx, "DelEntityDom1", "Entity2", auditRef);

        try {
            zms.getEntity(mockDomRsrcCtx, "DelEntityDom1", "Entity1");
            fail();
        } catch (Exception ex) {
            assertTrue(true);
        }

        try {
            zms.getEntity(mockDomRsrcCtx, "DelEntityDom1", "Entity2");
            fail();
        } catch (Exception ex) {
            assertTrue(true);
        }

        zms.deleteTopLevelDomain(mockDomRsrcCtx, "DelEntityDom1", auditRef);
    }

    @Test
    public void testDeleteEntityMissingAuditRef() {

        TestAuditLogger alogger = new TestAuditLogger();
        ZMSImpl zmsImpl = getZmsImpl(alogger);

        String domain = "testDeleteEntityMissingAuditRef";
        TopLevelDomain dom = createTopLevelDomainObject(
                domain, "Test Domain1", "testOrg", adminUser);
        dom.setAuditEnabled(true);
        zmsImpl.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom);

        Entity entity = createEntityObject("Entity1");
        zmsImpl.putEntity(mockDomRsrcCtx, domain, "Entity1", auditRef, entity);

        try {
            zmsImpl.deleteEntity(mockDomRsrcCtx, domain, "Entity1", null);
            fail("requesterror not thrown by deleteEntity.");
        } catch (ResourceException ex) {
            assertEquals(400, ex.getCode());
            assertTrue(ex.getMessage().contains("Audit reference required"));
        } finally {
            zmsImpl.deleteTopLevelDomain(mockDomRsrcCtx, domain, auditRef);
        }
    }

    @Test
    public void testGetUserToken() {
        
        // Use real Principal Authority to verify signatures
        PrincipalAuthority principalAuthority = new com.yahoo.athenz.auth.impl.PrincipalAuthority();
        principalAuthority.setKeyStore(zms);

        Authority userAuthority = new com.yahoo.athenz.common.server.debug.DebugUserAuthority();
        
        String userId = "george";
        Principal principal = SimplePrincipal.create("user", userId, userId + ":password",
                0, userAuthority);
        assertNotNull(principal);
        ((SimplePrincipal) principal).setUnsignedCreds(userId);
        ResourceContext rsrcCtx1 = createResourceContext(principal);

        zms.privateKey = new ServerPrivateKey(Crypto.loadPrivateKey(Crypto.ybase64DecodeString(privKey)), "0");
        loadServerPublicKeys(zms);

        UserToken token = zms.getUserToken(rsrcCtx1, userId, null, null);
        assertNotNull(token);
        assertTrue(token.getToken().startsWith("v=U1;d=user;n=" + userId + ";"));
        assertTrue(token.getToken().contains(";h=localhost"));
        assertTrue(token.getToken().contains(";i=10.11.12.13"));
        assertTrue(token.getToken().contains(";k=0"));
        // Verify signature
        Principal principalToVerify = principalAuthority.authenticate(token.getToken(), "10.11.12.13", "GET", null);
        assertNotNull(principalToVerify);

        zms.privateKey = new ServerPrivateKey(Crypto.loadPrivateKey(Crypto.ybase64DecodeString(privKeyK1)), "1");

        token = zms.getUserToken(rsrcCtx1, userId, null, false);
        assertNotNull(token);
        assertTrue(token.getToken().contains("k=1"));
        // Verify signature
        principalToVerify = principalAuthority.authenticate(token.getToken(), "10.11.12.13", "GET", null);
        assertNotNull(principalToVerify);

        zms.privateKey = new ServerPrivateKey(Crypto.loadPrivateKey(Crypto.ybase64DecodeString(privKeyK2)), "2");

        token = zms.getUserToken(rsrcCtx1, userId, null, null);
        assertNotNull(token);
        assertTrue(token.getToken().contains("k=2"));
        // Verify signature
        principalToVerify = principalAuthority.authenticate(token.getToken(), "10.11.12.13", "GET", null);
        assertNotNull(principalToVerify);
    }
    
    @Test
    public void testGetUserTokenAuthorizedService() {
        
        Authority userAuthority = new com.yahoo.athenz.common.server.debug.DebugUserAuthority();
        
        String userId = "george";
        Principal principal = SimplePrincipal.create("user", userId, userId + ":password",
                0, userAuthority);
        assertNotNull(principal);
        ((SimplePrincipal) principal).setUnsignedCreds(userId);
        ResourceContext rsrcCtx1 = createResourceContext(principal);
        
        zms.privateKey = new ServerPrivateKey(Crypto.loadPrivateKey(Crypto.ybase64DecodeString(privKey)), "0");
        
        UserToken token = zms.getUserToken(rsrcCtx1, userId, "coretech.storage", null);
        assertNotNull(token);
        assertTrue(token.getToken().contains(";b=coretech.storage;"));
        
        token = zms.getUserToken(rsrcCtx1, userId, "coretech.storage,sports.hockey", false);
        assertNotNull(token);
        assertTrue(token.getToken().contains(";b=coretech.storage,sports.hockey;"));
    }
        
    @Test
    public void testGetUserTokenInvalidAuthorizedService() {
        
        Authority userAuthority = new com.yahoo.athenz.common.server.debug.DebugUserAuthority();
        
        String userId = "george";
        Principal principal = SimplePrincipal.create("user", userId, userId + ":password",
                0, userAuthority);
        assertNotNull(principal);
        ((SimplePrincipal) principal).setUnsignedCreds(userId);
        ResourceContext rsrcCtx1 = createResourceContext(principal);
        
        try {
            zms.getUserToken(rsrcCtx1, userId, "coretech.storage,sports", null);
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 401);
            assertTrue(ex.getMessage().contains("getUserToken: Service sports is not authorized in ZMS"));
        }

        try {
            zms.getUserToken(rsrcCtx1, userId, "baseball", false);
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 401);
            assertTrue(ex.getMessage().contains("getUserToken: Service baseball is not authorized in ZMS"));
        }
        
        try {
            zms.getUserToken(rsrcCtx1, userId, "hat trick", false);
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 401);
            assertTrue(ex.getMessage().contains("getUserToken: Service hat trick is not authorized in ZMS"));
        }
    }
    
    @Test
    public void testGetUserTokenExpiredIssueTime() {
        
        // Use real Principal Authority to verify signatures
        PrincipalAuthority principalAuthority = new com.yahoo.athenz.auth.impl.PrincipalAuthority();
        principalAuthority.setKeyStore(zms);

        // we're going to set the issue time 2 hours before the current time
        
        long issueTime = (System.currentTimeMillis() / 1000) - 7200;
        
        Authority userAuthority = new com.yahoo.athenz.common.server.debug.DebugUserAuthority();
        
        String userId = "george";
        Principal principal = SimplePrincipal.create("user", userId, userId + ":password",
                0, userAuthority);
        assertNotNull(principal);
        ((SimplePrincipal) principal).setUnsignedCreds(userId);
        ResourceContext rsrcCtx1 = createResourceContext(principal);

        zms.privateKey = new ServerPrivateKey(Crypto.loadPrivateKey(Crypto.ybase64DecodeString(privKey)), "0");
        loadServerPublicKeys(zms);

        UserToken token = zms.getUserToken(rsrcCtx1, userId, null, null);
        assertNotNull(token);
        // Verify signature
        Principal principalToVerify = principalAuthority.authenticate(token.getToken(), "10.11.12.13", "GET", null);
        assertNotNull(principalToVerify);
        
        // verify that the issue time for the user token is not our issue time
        
        PrincipalToken pToken = new PrincipalToken(token.getToken());
        assertNotEquals(pToken.getTimestamp(), issueTime);
        
        // verify that our expiry is close to 1 hour default value
        
        assertTrue(pToken.getExpiryTime() - (System.currentTimeMillis() / 1000) > 3500);
    }
    
    @Test
    public void testGetUserTokenMismatchName() {
        int code = 401;
        
        Authority userAuthority = new com.yahoo.athenz.common.server.debug.DebugUserAuthority();
        
        String userId = "user1";
        Principal principal = SimplePrincipal.create("user", userId, userId + ":password",
                0, userAuthority);
        assertNotNull(principal);
        ((SimplePrincipal) principal).setUnsignedCreds(userId);
        ResourceContext rsrcCtx1 = createResourceContext(principal);
        
        try {
            zms.getUserToken(rsrcCtx1, "user2", null, null);
            fail("unauthorizederror not thrown.");
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), code);
        }
        
        try {
            zms.getUserToken(rsrcCtx1, "_self", null, false);
            fail("unauthorizederror not thrown.");
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), code);
        }
        
        try {
            zms.getUserToken(rsrcCtx1, "self", null, false);
            fail("unauthorizederror not thrown.");
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), code);
        }
    }
    
    @Test
    public void testGetUserTokenDefaultSelfName() {

        // Use real Principal Authority to verify signatures
        PrincipalAuthority principalAuthority = new com.yahoo.athenz.auth.impl.PrincipalAuthority();
        principalAuthority.setKeyStore(zms);

        Authority userAuthority = new com.yahoo.athenz.common.server.debug.DebugUserAuthority();
        
        String userId = "user10";
        Principal principal = SimplePrincipal.create("user", userId, userId + ":password",
                0, userAuthority);
        assertNotNull(principal);
        ((SimplePrincipal) principal).setUnsignedCreds(userId);
        ResourceContext rsrcCtx1 = createResourceContext(principal);

        zms.privateKey = new ServerPrivateKey(Crypto.loadPrivateKey(Crypto.ybase64DecodeString(privKey)), "0");
        loadServerPublicKeys(zms);

        UserToken token = zms.getUserToken(rsrcCtx1, "_self_", null, false);
        assertNotNull(token);
        assertTrue(token.getToken().startsWith("v=U1;d=user;n=" + userId + ";"));
        assertTrue(token.getToken().contains(";h=localhost"));
        assertTrue(token.getToken().contains(";i=10.11.12.13"));
        assertTrue(token.getToken().contains(";k=0"));
        // Verify signature
        Principal principalToVerify = principalAuthority.authenticate(token.getToken(), "10.11.12.13", "GET", null);
        assertNotNull(principalToVerify);
    }
    
    @Test
    public void testGetUserTokenBadAuthority() {
        int code = 401;
        
        Authority principalAuthority = new com.yahoo.athenz.common.server.debug.DebugPrincipalAuthority();
        Principal principal = SimplePrincipal.create("user", "user1", "v=U1;d=user;n=user1;s=signature",
                0, principalAuthority);
        ResourceContext rsrcCtx1 = createResourceContext(principal);
        
        try {
            zms.getUserToken(rsrcCtx1, "user1", null, null);
            fail("unauthorizederror not thrown.");
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), code);
        }
    }
    
    @Test
    public void testGetUserTokenNullAuthority() {
        int code = 401;
        
        Principal principal = SimplePrincipal.create("user", "user1", "v=U1;d=user;n=user1;s=signature");
        ResourceContext rsrcCtx1 = createResourceContext(principal);

        try {
            zms.getUserToken(rsrcCtx1, "user1", null, null);
            fail("unauthorizederror not thrown.");
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), code);
        }
    }

    @Test
    public void testGetTenantResourceGroupRoles() {

        String domain = "testGetTenantResourceGroupRoles";
        TopLevelDomain dom = createTopLevelDomainObject(
                domain, "Test Domain1", "testOrg", adminUser);
        dom.setAuditEnabled(true);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom);

        List<TenantRoleAction> roleActions = new ArrayList<>();
        for (Struct.Field f : TABLE_PROVIDER_ROLE_ACTIONS) {
            roleActions.add(new TenantRoleAction().setRole(f.name()).setAction(
                    (String) f.value()));
        }
        String serviceName  = "storage";
        String tenantDomain = "tenantTestDeleteTenantRoles";
        String resourceGroup = "Group1";

        TenantResourceGroupRoles tenantRoles = new TenantResourceGroupRoles().setDomain(domain)
                .setService(serviceName).setTenant(tenantDomain)
                .setRoles(roleActions).setResourceGroup(resourceGroup);
        zms.putTenantResourceGroupRoles(mockDomRsrcCtx, domain, serviceName, tenantDomain, resourceGroup,
                auditRef, tenantRoles);

        TenantResourceGroupRoles tRoles = zms.getTenantResourceGroupRoles(mockDomRsrcCtx, domain, serviceName,
                tenantDomain, resourceGroup);
        assertNotNull(tRoles);
        assertEquals(domain.toLowerCase(), tRoles.getDomain());
        assertEquals(serviceName.toLowerCase(), tRoles.getService());
        assertEquals(tenantDomain.toLowerCase(), tRoles.getTenant());
        assertEquals(resourceGroup.toLowerCase(), tRoles.getResourceGroup());
        assertEquals(TABLE_PROVIDER_ROLE_ACTIONS.size(), tRoles.getRoles().size());

        zms.deleteTopLevelDomain(mockDomRsrcCtx, domain, auditRef);
    }

    @Test
    public void testGetTenantResourceGroupRolesInvalidDomain() {

        try {
            zms.getTenantResourceGroupRoles(mockDomRsrcCtx, "invalid-domain", "api",
                    "tenant", "table1");
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 404);
        }
    }

    @Test
    public void testDeleteTenantResourceGroupRoles() {

        String domain = "testDeleteTenantResourceGroupRoles";
        TopLevelDomain dom = createTopLevelDomainObject(
                domain, "Test Domain1", "testOrg", adminUser);
        dom.setAuditEnabled(true);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom);

        List<TenantRoleAction> roleActions = new ArrayList<>();
        for (Struct.Field f : TABLE_PROVIDER_ROLE_ACTIONS) {
            roleActions.add(new TenantRoleAction().setRole(f.name()).setAction(
                    (String) f.value()));
        }
        String serviceName  = "storage";
        String tenantDomain = "tenantTestDeleteTenantRoles";
        String resourceGroup = "Group1";

        TenantResourceGroupRoles tenantRoles = new TenantResourceGroupRoles().setDomain(domain)
                .setService(serviceName).setTenant(tenantDomain)
                .setRoles(roleActions).setResourceGroup(resourceGroup);
        zms.putTenantResourceGroupRoles(mockDomRsrcCtx, domain, serviceName, tenantDomain, resourceGroup,
                auditRef, tenantRoles);

        TenantResourceGroupRoles tRoles = zms.getTenantResourceGroupRoles(mockDomRsrcCtx, domain, serviceName,
                tenantDomain, resourceGroup);
        assertNotNull(tRoles);

        zms.deleteTenantResourceGroupRoles(mockDomRsrcCtx, domain, serviceName, tenantDomain, resourceGroup, auditRef);

        tRoles = zms.getTenantResourceGroupRoles(mockDomRsrcCtx, domain, serviceName, tenantDomain, resourceGroup);
        assertNotNull(tRoles);
        assertEquals(domain.toLowerCase(), tRoles.getDomain());
        assertEquals(serviceName.toLowerCase(), tRoles.getService());
        assertEquals(tenantDomain.toLowerCase(), tRoles.getTenant());
        assertEquals(resourceGroup.toLowerCase(), tRoles.getResourceGroup());
        assertEquals(0, tRoles.getRoles().size());

        zms.deleteTopLevelDomain(mockDomRsrcCtx, domain, auditRef);
    }
 
    @Test
    public void testValidatedAdminUsersThrowException() {
        try {
            zms.validatedAdminUsers(null);
            fail("requesterror not thrown.");
        } catch (ResourceException e) {
            assertEquals(e.getCode(), 400);
        }
    }

    @Test
    public void testPutDefaultAdminsInvalidDomain() {

        DefaultAdmins admins = new DefaultAdmins();

        try {
            zms.putDefaultAdmins(mockDomRsrcCtx, "sports", auditRef, admins);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), ResourceException.NOT_FOUND);
        }
    }

    @Test
    public void testPutDefaultAdmins() {

        TopLevelDomain sportsDomain = createTopLevelDomainObject("sports",
                "Test domain for sports", "testOrg", adminUser);
        try {
            zms.deleteTopLevelDomain(mockDomRsrcCtx, sportsDomain.getName(), auditRef);
        } catch (ResourceException ignored) {
        }
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, sportsDomain);

        List<String> adminList = new ArrayList<>();
        DefaultAdmins admins = new DefaultAdmins();

        // negative test, pass an empty list
        admins.setAdmins(adminList);
        zms.putDefaultAdmins(mockDomRsrcCtx, "sports", auditRef, admins);

        Role role = zms.getRole(mockDomRsrcCtx, "sports", "admin", false, false, false);
        assertNotNull(role);
        assertEquals(role.getName(), "sports:role.admin");
        List<RoleMember> members = role.getRoleMembers();
        assertNotNull(members);
        assertEquals(members.size(), 1);
        assertEquals(members.get(0).getMemberName(), adminUser);
        
        // positive test
        adminList.add("user.sports_admin");
        adminList.add("sports.fantasy");
        adminList.add("user.joeschmoe");
        adminList.add("user.johndoe");

        admins.setAdmins(adminList);
        zms.putDefaultAdmins(mockDomRsrcCtx, "sports", auditRef, admins);

        role = zms.getRole(mockDomRsrcCtx, "sports", "admin", false, false, false);
        assertNotNull(role);
        assertEquals(role.getName(), "sports:role.admin");
        members = role.getRoleMembers();
        assertNotNull(members);
        assertEquals(members.size(), 5);

        // add user.testadminuser to the list for verification since it should be
        // there when the domain was added
        adminList.add(adminUser);
        for (String admin : adminList) {
            boolean found = false;
            for (RoleMember memberFromRole : members) {
                if (memberFromRole.getMemberName().equalsIgnoreCase(admin)) {
                    found = true;
                    break;
                }
            }
            assertTrue(found);
        }

        zms.deleteTopLevelDomain(mockDomRsrcCtx, sportsDomain.getName(), auditRef);
    }

    @Test
    public void testPutDefaultAdminsMissingAuditRef() {

        TestAuditLogger alogger = new TestAuditLogger();
        ZMSImpl zmsImpl = getZmsImpl(alogger);

        String domain = "testPutDefaultAdminsMissingAuditRef";
        TopLevelDomain dom = createTopLevelDomainObject(
                domain, "Test Domain1", "testOrg", adminUser);
        dom.setAuditEnabled(true);
        zmsImpl.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom);

        List<String> adminList = new ArrayList<>();
        adminList.add("user.sports_admin");
        adminList.add("sports.fantasy");
        DefaultAdmins admins = new DefaultAdmins();
        admins.setAdmins(adminList);
        try {
            zmsImpl.putDefaultAdmins(mockDomRsrcCtx, domain, null, admins);
            fail("requesterror not thrown by putDefaultAdmins.");
        } catch (ResourceException ex) {
            assertEquals(400, ex.getCode());
            assertTrue(ex.getMessage().contains("Audit reference required"));
        } finally {
            zmsImpl.deleteTopLevelDomain(mockDomRsrcCtx, domain, auditRef);
        }
    }
    
    @Test
    public void testPutDefaultAdminsNoAdminRole() {

        TopLevelDomain sportsDomain = createTopLevelDomainObject("sports",
                "Test domain for sports", "testOrg", adminUser);
        
        try {
            zms.deleteTopLevelDomain(mockDomRsrcCtx, sportsDomain.getName(), auditRef);
        } catch (ResourceException ignored) {
        }
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, sportsDomain);

        // since we can't delete the admin role anymore
        // we're going to access the store object directly to
        // accomplish that for our unit test
        
        zms.dbService.executeDeleteRole(mockDomRsrcCtx, sportsDomain.getName(), "admin",
                auditRef, "unittest");
        
        List<String> adminList = new ArrayList<>();
        DefaultAdmins admins = new DefaultAdmins();
        adminList.add("user.sports_admin");
        adminList.add("sports.fantasy");
        adminList.add("user.joeschmoe");
        adminList.add("user.johndoe");
        adminList.add(adminUser);

        admins.setAdmins(adminList);
        zms.putDefaultAdmins(mockDomRsrcCtx, "sports", auditRef, admins);

        Role role = zms.getRole(mockDomRsrcCtx, "sports", "admin", false, false, false);
        assertNotNull(role);
        assertEquals(role.getName(), "sports:role.admin");
        List<RoleMember> members = role.getRoleMembers();
        assertNotNull(members);
        assertEquals(members.size(), 5);

        // add user.testadminuser to the list for verification since it should be
        // there when the domain was added
        adminList.add(adminUser);
        for (String admin : adminList) {
            boolean found = false;
            for (RoleMember memberFromRole : members) {
                if (memberFromRole.getMemberName().equalsIgnoreCase(admin)) {
                    found = true;
                    break;
                }
            }
            assertTrue(found);
        }

        zms.deleteTopLevelDomain(mockDomRsrcCtx, sportsDomain.getName(), auditRef);
    }

    @Test
    public void testPutDefaultAdmins_NoAdminPolicy() {

        TopLevelDomain sportsDomain = createTopLevelDomainObject("sports",
                "Test domain for sports", "testOrg", adminUser);
        try {
            zms.deleteTopLevelDomain(mockDomRsrcCtx, sportsDomain.getName(), auditRef);
        } catch (ResourceException ignored) {
        }
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, sportsDomain);

        // since we can't delete the admin policy anymore
        // we're going to access the store object directly to
        // accomplish that for our unit test
        
        zms.dbService.executeDeletePolicy(mockDomRsrcCtx, sportsDomain.getName(), "admin",
                auditRef, "unittest");

        List<String> adminList = new ArrayList<>();
        DefaultAdmins admins = new DefaultAdmins();
        adminList.add("user.sports_admin");
        adminList.add("sports.fantasy");
        adminList.add("user.joeschmoe");
        adminList.add("user.johndoe");

        admins.setAdmins(adminList);
        zms.putDefaultAdmins(mockDomRsrcCtx, "sports", auditRef, admins);

        // Validate that admin policy has been added back
        Policy policy = zms.getPolicy(mockDomRsrcCtx, "sports", "admin");
        assertNotNull(policy);
        assertEquals(policy.getName(), "sports:policy.admin");
        List<Assertion> assertions = policy.getAssertions();
        boolean foundAssertion = false;
        for (Assertion assertion : assertions) {
            if ("sports:*".equals(assertion.getResource())
                    && "*".equals(assertion.getAction())
                    && "sports:role.admin".equals(assertion.getRole())) {
                foundAssertion = true;
                break;
            }
        }
        assertTrue(foundAssertion);

        Role role = zms.getRole(mockDomRsrcCtx, "sports", "admin", false, false, false);
        assertNotNull(role);
        assertEquals(role.getName(), "sports:role.admin");
        List<RoleMember> members = role.getRoleMembers();
        assertNotNull(members);
        assertEquals(members.size(), 5);

        for (String admin : adminList) {
            boolean found = false;
            for (RoleMember memberFromRole : members) {
                if (memberFromRole.getMemberName().equalsIgnoreCase(admin)) {
                    found = true;
                    break;
                }
            }
            assertTrue(found);
        }

        zms.deleteTopLevelDomain(mockDomRsrcCtx, sportsDomain.getName(), auditRef);
    }

    @Test
    public void testPutDefaultAdmins_AdminPolicyWithDeny() {

        TopLevelDomain sportsDomain = createTopLevelDomainObject("sports",
                "Test domain for sports", "testOrg", adminUser);
        try {
            zms.deleteTopLevelDomain(mockDomRsrcCtx, sportsDomain.getName(), auditRef);
        } catch (ResourceException ignored) {
        }
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, sportsDomain);

        // Add policy which will DENY admin role
        Policy policy = new Policy();
        policy.setName(ZMSUtils.policyResourceName("sports", "denyAdmin"));
        Assertion assertion = new Assertion();
        assertion.setResource("sports:*");
        assertion.setAction("*");
        assertion.setRole("sports:role.admin");
        assertion.setEffect(AssertionEffect.DENY);
        List<Assertion> assertions = new ArrayList<>();
        assertions.add(assertion);
        policy.setAssertions(assertions);
        zms.putPolicy(mockDomRsrcCtx, "sports", "denyAdmin", auditRef, policy);

        List<String> adminList = new ArrayList<>();
        DefaultAdmins admins = new DefaultAdmins();
        adminList.add("user.sports_admin");
        adminList.add("sports.fantasy");
        adminList.add("user.joeschmoe");
        adminList.add("user.johndoe");

        admins.setAdmins(adminList);
        zms.putDefaultAdmins(mockDomRsrcCtx, "sports", auditRef, admins);

        // denyAdmin policy should be deleted by putDefaultAdmins validation
        try {
            policy = zms.getPolicy(mockDomRsrcCtx, "sports", "denyAdmin");
            assertNotNull(policy); // should not be found
        } catch (ResourceException ex) {
            // policy should not be found
            if (ex.getCode() != 404) {
                throw ex;
            }
        }

        Role role = zms.getRole(mockDomRsrcCtx, "sports", "admin", false, false, false);
        assertNotNull(role);
        assertEquals(role.getName(), "sports:role.admin");
        List<RoleMember> members = role.getRoleMembers();
        assertNotNull(members);
        assertEquals(members.size(), 5);

        for (String admin : adminList) {
            boolean found = false;
            for (RoleMember memberFromRole : members) {
                if (memberFromRole.getMemberName().equalsIgnoreCase(admin)) {
                    found = true;
                    break;
                }
            }
            assertTrue(found);
        }

        zms.deleteTopLevelDomain(mockDomRsrcCtx, sportsDomain.getName(), auditRef);
    }

    @Test
    public void testPutDefaultAdmins_DenyIndirectRole() {

        TopLevelDomain sportsDomain = createTopLevelDomainObject("sports",
                "Test domain for sports", "testOrg", adminUser);
        try {
            zms.deleteTopLevelDomain(mockDomRsrcCtx, sportsDomain.getName(), auditRef);
        } catch (ResourceException ignored) {
        }
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, sportsDomain);

        // Add role indirectRole
        Role role = new Role();
        role.setName("sports:role.indirectRole");
        List<RoleMember> members = new ArrayList<>();
        members.add(new RoleMember().setMemberName("user.johnadams"));
        members.add(new RoleMember().setMemberName("user.sports_admin"));
        members.add(new RoleMember().setMemberName("sports.fantasy"));
        members.add(new RoleMember().setMemberName("user.joeschmoe"));
        members.add(new RoleMember().setMemberName("user.johndoe"));
        role.setRoleMembers(members);
        role.setTrust(null);
        zms.putRole(mockDomRsrcCtx, "sports", "indirectRole", auditRef, role);

        // Add policy which will DENY indirectRole role
        Policy policy = new Policy();
        policy.setName(ZMSUtils.policyResourceName("sports", "denyIndirectRole"));
        Assertion assertion = new Assertion();
        assertion.setResource("sports:*");
        assertion.setAction("*");
        assertion.setRole("sports:role.indirectRole");
        assertion.setEffect(AssertionEffect.DENY);
        List<Assertion> assertions = new ArrayList<>();
        assertions.add(assertion);
        policy.setAssertions(assertions);
        zms.putPolicy(mockDomRsrcCtx, "sports", "denyIndirectRole", auditRef, policy);

        List<String> adminList = new ArrayList<>();
        DefaultAdmins admins = new DefaultAdmins();
        adminList.add("user.sports_admin");
        adminList.add("sports.fantasy");
        adminList.add("user.joeschmoe");
        adminList.add("user.johndoe");

        admins.setAdmins(adminList);
        zms.putDefaultAdmins(mockDomRsrcCtx, "sports", auditRef, admins);

        role = zms.getRole(mockDomRsrcCtx, "sports", "indirectRole", false, false, false);
        assertNotNull(role);
        assertEquals(role.getName(), "sports:role.indirectRole".toLowerCase());
        members = role.getRoleMembers();
        assertEquals(members.size(), 1);

        for (String admin : adminList) {
            boolean found = false;
            for (RoleMember memberFromRole : members) {
                if (memberFromRole.getMemberName().equalsIgnoreCase(admin)) {
                    found = true;
                    break;
                }
            }
            assertFalse(found);
        }

        role = zms.getRole(mockDomRsrcCtx, "sports", "admin", false, false, false);
        assertNotNull(role);
        assertEquals(role.getName(), "sports:role.admin");
        members = role.getRoleMembers();
        assertNotNull(members);
        assertEquals(members.size(), 5);

        for (String admin : adminList) {
            boolean found = false;
            for (RoleMember memberFromRole : members) {
                if (memberFromRole.getMemberName().equalsIgnoreCase(admin)) {
                    found = true;
                    break;
                }
            }
            assertTrue(found);
        }

        zms.deleteTopLevelDomain(mockDomRsrcCtx, sportsDomain.getName(), auditRef);
    }

    @Test
    public void testGetSignedDomains() {

        loadServerPublicKeys(zms);

        // create multiple top level domains
        TopLevelDomain dom1 = createTopLevelDomainObject("SignedDom1",
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        // set the meta attributes for domain
        
        DomainMeta meta = createDomainMetaObject("Tenant Domain1", null, true, false, "12345", 0);
        zms.putDomainMeta(mockDomRsrcCtx, "signeddom1", auditRef, meta);
        meta = createDomainMetaObject("Tenant Domain1", null, true, false, "12345", 0);
        zms.putDomainSystemMeta(mockDomRsrcCtx, "signeddom1", "account", auditRef, meta);

        TopLevelDomain dom2 = createTopLevelDomainObject("SignedDom2",
                "Test Domain2", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom2);

        meta = createDomainMetaObject("Tenant Domain2", null, false, false, "12346", null);
        zms.putDomainMeta(mockDomRsrcCtx, "signeddom2", auditRef, meta);
        meta = createDomainMetaObject("Tenant Domain2", null, false, false, "12346", null);
        zms.putDomainSystemMeta(mockDomRsrcCtx, "signeddom2", "account", auditRef, meta);

        DomainList domList = zms.getDomainList(mockDomRsrcCtx, null, null, null, null,
                null, null, null, null, null);
        List<String> domNames = domList.getNames();
        int numDoms = domNames.size();

        zms.privateKey = new ServerPrivateKey(Crypto.loadPrivateKey(Crypto.ybase64DecodeString(privKey)), "0");

        Authority principalAuthority = new com.yahoo.athenz.common.server.debug.DebugPrincipalAuthority();
        Principal sysPrincipal = principalAuthority.authenticate("v=U1;d=sys;n=zts;s=signature",
                "10.11.12.13", "GET", null);
        ResourceContext rsrcCtx = createResourceContext(sysPrincipal);

        Response response = zms.getSignedDomains(rsrcCtx, null, null, null, null, null);
        SignedDomains sdoms = (SignedDomains) response.getEntity();

        assertNotNull(sdoms);
        List<SignedDomain> list = sdoms.getDomains();
        assertNotNull(list);
        assertEquals(list.size(), numDoms);

        boolean dom1Found = false;
        boolean dom2Found = false;
        for (SignedDomain sDomain : list) {
            String signature = sDomain.getSignature();
            String keyId = sDomain.getKeyId();
            String publicKey = zms.getPublicKey("sys.auth", "zms", keyId);
            DomainData domainData = sDomain.getDomain();
            if (domainData.getName().equals("signeddom1")) {
                assertEquals("12345", domainData.getAccount());
                dom1Found = true;
            } else if (domainData.getName().equals("signeddom2")) {
                assertEquals("12346", domainData.getAccount());
                dom2Found = true;
            }
            assertTrue(Crypto.verify(SignUtils.asCanonicalString(sDomain.getDomain()), Crypto.loadPublicKey(publicKey), signature));
        }
        assertTrue(dom1Found);
        assertTrue(dom2Found);

        zms.privateKey = new ServerPrivateKey(Crypto.loadPrivateKey(Crypto.ybase64DecodeString(privKeyK1)), "1");

        response = zms.getSignedDomains(rsrcCtx, null, null, "all", null, null);
        sdoms = (SignedDomains) response.getEntity();

        assertNotNull(sdoms);
        list = sdoms.getDomains();
        assertNotNull(list);
        assertEquals(list.size(), numDoms);

        for(SignedDomain sDomain : list) {
            String signature = sDomain.getSignature();
            String keyId = sDomain.getKeyId();
            String publicKey = zms.getPublicKey("sys.auth", "zms", keyId);
            assertTrue(Crypto.verify(SignUtils.asCanonicalString(sDomain.getDomain()), Crypto.loadPublicKey(publicKey), signature));
            
            // we now need to verify the policy struct signature as well
            
            SignedPolicies signedPolicies = sDomain.getDomain().getPolicies();
            signature = signedPolicies.getSignature();
            assertTrue(Crypto.verify(SignUtils.asCanonicalString(signedPolicies.getContents()), Crypto.loadPublicKey(publicKey), signature));
        }

        zms.privateKey = new ServerPrivateKey(Crypto.loadPrivateKey(Crypto.ybase64DecodeString(privKeyK2)), "2");

        response = zms.getSignedDomains(rsrcCtx, null, null, null, Boolean.TRUE, null);
        sdoms = (SignedDomains) response.getEntity();
        assertNotNull(sdoms);

        list = sdoms.getDomains();
        assertNotNull(list);
        assertEquals(list.size(), numDoms);

        for(SignedDomain sDomain : list) {
            String signature = sDomain.getSignature();
            String keyId = sDomain.getKeyId();
            String publicKey = zms.getPublicKey("sys.auth", "zms", keyId);
            assertTrue(Crypto.verify(SignUtils.asCanonicalString(sDomain.getDomain()), Crypto.loadPublicKey(publicKey), signature));
        }

        // test metaonly=true
        //
        response = zms.getSignedDomains(rsrcCtx, null, "tRuE", null, Boolean.FALSE, null);
        sdoms = (SignedDomains) response.getEntity();
        assertNotNull(sdoms);

        list = sdoms.getDomains();
        assertNotNull(list);
        assertEquals(list.size(), numDoms);

        for (SignedDomain sDomain : list) {
            String signature = sDomain.getSignature();
            assertTrue(signature == null || signature.isEmpty());
            String keyId = sDomain.getKeyId();
            assertTrue(keyId == null || keyId.isEmpty());
            DomainData ddata = sDomain.getDomain();
            assertNotNull(ddata);
            assertFalse(ddata.getName().isEmpty());
            assertNotNull(ddata.getModified());
            assertNull(ddata.getPolicies());
            assertNull(ddata.getRoles());
            assertNull(ddata.getServices());
        }

        // test metaonly=garbage
        //
        response = zms.getSignedDomains(rsrcCtx, null, "garbage", null, null, null);
        sdoms = (SignedDomains) response.getEntity();
        assertNotNull(sdoms);

        list = sdoms.getDomains();
        assertNotNull(list);
        assertEquals(list.size(), numDoms);

        for (SignedDomain sDomain : list) {
            String signature = sDomain.getSignature();
            String keyId = sDomain.getKeyId();
            String publicKey = zms.getPublicKey("sys.auth", "zms", keyId);
            assertTrue(Crypto.verify(SignUtils.asCanonicalString(sDomain.getDomain()), Crypto.loadPublicKey(publicKey), signature));
            DomainData ddata = sDomain.getDomain();
            assertNotNull(ddata.getPolicies());
            assertTrue(ddata.getRoles() != null && ddata.getRoles().size() > 0);
            assertNotNull(ddata.getServices());
        }

        // test metaonly=false
        //
        response = zms.getSignedDomains(rsrcCtx, null, "fAlSe", null, null, null);
        sdoms = (SignedDomains) response.getEntity();
        assertNotNull(sdoms);

        list = sdoms.getDomains();
        assertNotNull(list);
        assertEquals(list.size(), numDoms);

        for (SignedDomain sDomain : list) {
            String signature = sDomain.getSignature();
            String keyId = sDomain.getKeyId();
            String publicKey = zms.getPublicKey("sys.auth", "zms", keyId);
            assertTrue(Crypto.verify(SignUtils.asCanonicalString(sDomain.getDomain()), Crypto.loadPublicKey(publicKey), signature));
            DomainData ddata = sDomain.getDomain();
            assertNotNull(ddata.getPolicies());
            assertTrue(ddata.getRoles() != null && ddata.getRoles().size() > 0);
            assertNotNull(ddata.getServices());
        }

        // test bad tag format
        //
        String eTag  = "I am not good";
        response = zms.getSignedDomains(rsrcCtx, null, null, null, Boolean.TRUE, eTag);
        sdoms = (SignedDomains) response.getEntity();
        String eTag2 = response.getHeaderString("ETag");
        assertNotNull(eTag2);
        assertNotEquals(eTag, eTag2);
        list = sdoms.getDomains();
        assertNotNull(list);
        assertEquals(list.size(), numDoms);

        ZMSUtils.threadSleep(1000);

        Policy policy1 = createPolicyObject("SignedDom1", "Policy1");
        zms.putPolicy(mockDomRsrcCtx, "SignedDom1", "Policy1", auditRef, policy1);

        response = zms.getSignedDomains(rsrcCtx, null, null, null, true, eTag2);
        sdoms = (SignedDomains) response.getEntity();
        eTag = response.getHeaderString("ETag");
        assertNotNull(eTag);
        assertNotEquals(eTag, eTag2);
        list = sdoms.getDomains();
        assertNotNull(list);
        assertEquals(1, list.size());

        response = zms.getSignedDomains(rsrcCtx, null, null, null, Boolean.TRUE, eTag);
        assertEquals(304, response.getStatus());
        eTag2 = response.getHeaderString("ETag");

        assertNotNull(eTag2);
        assertEquals(eTag, eTag2);

        zms.deleteTopLevelDomain(mockDomRsrcCtx, "SignedDom1", auditRef);
        zms.deleteTopLevelDomain(mockDomRsrcCtx, "SignedDom2", auditRef);
    }
    
    @Test
    public void testGetSignedDomainsFiltered() {

        loadServerPublicKeys(zms);

        // create multiple top level domains
        TopLevelDomain dom1 = createTopLevelDomainObject("signeddom1filtered",
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        TopLevelDomain dom2 = createTopLevelDomainObject("signeddom2filtered",
                "Test Domain2", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom2);

        zms.privateKey = new ServerPrivateKey(Crypto.loadPrivateKey(Crypto.ybase64DecodeString(privKey)), "0");

        Response response = zms.getSignedDomains(mockDomRsrcCtx, "signeddom1filtered", null, null, null,  null);
        SignedDomains sdoms = (SignedDomains) response.getEntity();

        assertNotNull(sdoms);
        List<SignedDomain> list = sdoms.getDomains();
        assertNotNull(list);
        assertEquals(1, list.size());

        SignedDomain sDomain = list.get(0);
        String signature = sDomain.getSignature();
        String keyId = sDomain.getKeyId();
        String publicKey = zms.getPublicKey("sys.auth", "zms", keyId);
        assertTrue(Crypto.verify(SignUtils.asCanonicalString(sDomain.getDomain()), Crypto.loadPublicKey(publicKey), signature));
        assertEquals("signeddom1filtered", sDomain.getDomain().getName());

        // use domain=signeddom1filtered and metaonly=true
        //

        response = zms.getSignedDomains(mockDomRsrcCtx, "signeddom1filtered", "true", null, Boolean.TRUE, null);
        sdoms = (SignedDomains) response.getEntity();

        assertNotNull(sdoms);
        list = sdoms.getDomains();
        assertNotNull(list);
        assertEquals(1, list.size());

        sDomain = list.get(0);
        signature = sDomain.getSignature();
        assertTrue(signature == null || signature.isEmpty());
        keyId = sDomain.getKeyId();
        assertTrue(keyId == null || keyId.isEmpty());
        DomainData ddata = sDomain.getDomain();
        assertEquals("signeddom1filtered", ddata.getName());
        assertNotNull(ddata.getModified());
        assertNull(ddata.getPolicies());
        assertNull(ddata.getRoles());
        assertNull(ddata.getServices());

        // no changes, we should still get the same data back
        // we're going to pass the domain name with caps and
        // make sure we still get back our domain

        response = zms.getSignedDomains(mockDomRsrcCtx, "SignedDom1Filtered", null, null, Boolean.TRUE, null);
        sdoms = (SignedDomains) response.getEntity();

        assertNotNull(sdoms);
        list = sdoms.getDomains();
        assertNotNull(list);
        assertEquals(1, list.size());

        sDomain = list.get(0);
        signature = sDomain.getSignature();
        keyId = sDomain.getKeyId();
        publicKey = zms.getPublicKey("sys.auth", "zms", keyId);
        assertTrue(Crypto.verify(SignUtils.asCanonicalString(sDomain.getDomain()), Crypto.loadPublicKey(publicKey), signature));
        assertEquals("signeddom1filtered", sDomain.getDomain().getName());
        
        zms.deleteTopLevelDomain(mockDomRsrcCtx, "signeddom1filtered", auditRef);
        zms.deleteTopLevelDomain(mockDomRsrcCtx, "signeddom2filtered", auditRef);
    }

    @Test
    public void testGetSignedDomainsNotSystemPrincipal() {

        // create multiple top level domains
        TopLevelDomain dom1 = createTopLevelDomainObject("SignedDom1",
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        Response response = zms.getSignedDomains(mockDomRsrcCtx, null, null, null, Boolean.TRUE, null);
        assertEquals(response.getStatus(), ResourceException.BAD_REQUEST);

        zms.deleteTopLevelDomain(mockDomRsrcCtx, "SignedDom1", auditRef);
    }

    @Test
    public void testGetAccess() {

        TopLevelDomain dom1 = createTopLevelDomainObject("AccessDom1",
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        Role role1 = createRoleObject("AccessDom1", "Role1", null, "user.user1",
                "user.user3");
        zms.putRole(mockDomRsrcCtx, "AccessDom1", "Role1", auditRef, role1);

        Role role2 = createRoleObject("AccessDom1", "Role2", null, "user.user2",
                "user.user3");
        zms.putRole(mockDomRsrcCtx, "AccessDom1", "Role2", auditRef, role2);

        Policy policy1 = createPolicyObject("AccessDom1", "Policy1", "Role1",
                "UPDATE", "AccessDom1:resource1", AssertionEffect.ALLOW);
        zms.putPolicy(mockDomRsrcCtx, "AccessDom1", "Policy1", auditRef, policy1);

        Policy policy2 = createPolicyObject("AccessDom1", "Policy2", "Role2",
                "CREATE", "AccessDom1:resource2", AssertionEffect.DENY);
        zms.putPolicy(mockDomRsrcCtx, "AccessDom1", "Policy2", auditRef, policy2);

        Policy policy3 = createPolicyObject("AccessDom1", "Policy3", "Role2",
                "*", "AccessDom1:resource3", AssertionEffect.ALLOW);
        zms.putPolicy(mockDomRsrcCtx, "AccessDom1", "Policy3", auditRef, policy3);

        Policy policy4 = createPolicyObject("AccessDom1", "Policy4", "Role2",
                "DELETE", "accessdom1:*", AssertionEffect.ALLOW);
        zms.putPolicy(mockDomRsrcCtx, "AccessDom1", "Policy4", auditRef, policy4);

        Policy policy5 = createPolicyObject("AccessDom1", "Policy5", "Role1",
                "READ", "accessdom1:*", AssertionEffect.ALLOW);
        zms.putPolicy(mockDomRsrcCtx, "AccessDom1", "Policy5", auditRef, policy5);

        Policy policy6 = createPolicyObject("AccessDom1", "Policy6", "Role1",
                "READ", "AccessDom1:resource6", AssertionEffect.DENY);
        zms.putPolicy(mockDomRsrcCtx, "AccessDom1", "Policy6", auditRef, policy6);

        // user1 and user3 have access to UPDATE/resource1

        Authority principalAuthority = new com.yahoo.athenz.common.server.debug.DebugPrincipalAuthority();
        Principal principal1 = principalAuthority.authenticate("v=U1;d=user;n=user1;s=signature",
                "10.11.12.13", "GET", null);
        ResourceContext rsrcCtx1 = createResourceContext(principal1);
        Principal principal2 = principalAuthority.authenticate("v=U1;d=user;n=user2;s=signature",
                "10.11.12.13", "GET", null);
        ResourceContext rsrcCtx2 = createResourceContext(principal2);
        Principal principal3 = principalAuthority.authenticate("v=U1;d=user;n=user3;s=signature",
                "10.11.12.13", "GET", null);
        ResourceContext rsrcCtx3 = createResourceContext(principal3);

        Access access = zms.getAccess(rsrcCtx1, "UPDATE", "AccessDom1:resource1",
                "AccessDom1", null);
        assertTrue(access.getGranted());

        access = zms.getAccess(rsrcCtx2, "UPDATE", "AccessDom1:resource1",
                "AccessDom1", null);
        assertFalse(access.getGranted());

        access = zms.getAccess(rsrcCtx3, "UPDATE", "AccessDom1:resource1",
                "AccessDom1", null);
        assertTrue(access.getGranted());

        // same set as before with no trust domain field

        access = zms.getAccess(rsrcCtx1, "UPDATE", "AccessDom1:resource1",
                null, null);
        assertTrue(access.getGranted());

        access = zms.getAccess(rsrcCtx2, "UPDATE", "AccessDom1:resource1",
                null, null);
        assertFalse(access.getGranted());

        access = zms.getAccess(rsrcCtx3, "UPDATE", "AccessDom1:resource1",
                null, null);
        assertTrue(access.getGranted());

        // all three have no access to CREATE action on resource1

        access = zms.getAccess(rsrcCtx1, "CREATE", "AccessDom1:resource1",
                "AccessDom1", null);
        assertFalse(access.getGranted());

        access = zms.getAccess(rsrcCtx2, "CREATE", "AccessDom1:resource1",
                "AccessDom1", null);
        assertFalse(access.getGranted());

        access = zms.getAccess(rsrcCtx3, "CREATE", "AccessDom1:resource1",
                "AccessDom1", null);
        assertFalse(access.getGranted());

        // all three have no access to invalid domain name on resource 1

        access = zms.getAccess(rsrcCtx1, "CREATE", "AccessDom1:resource1",
                "AccessDom2", null);
        assertFalse(access.getGranted());

        access = zms.getAccess(rsrcCtx2, "CREATE", "AccessDom1:resource1",
                "AccessDom2", null);
        assertFalse(access.getGranted());

        access = zms.getAccess(rsrcCtx3, "CREATE", "AccessDom1:resource1",
                "AccessDom2", null);
        assertFalse(access.getGranted());

        // same as before with no trust domain field

        access = zms.getAccess(rsrcCtx1, "CREATE", "AccessDom1:resource1",
                null, null);
        assertFalse(access.getGranted());

        access = zms.getAccess(rsrcCtx2, "CREATE", "AccessDom1:resource1",
                null, null);
        assertFalse(access.getGranted());

        access = zms.getAccess(rsrcCtx3, "CREATE", "AccessDom1:resource1",
                null, null);
        assertFalse(access.getGranted());

        // all three should have deny access to resource 2

        access = zms.getAccess(rsrcCtx1, "CREATE", "AccessDom1:resource2",
                "AccessDom1", null);
        assertFalse(access.getGranted());

        access = zms.getAccess(rsrcCtx2, "CREATE", "AccessDom1:resource2",
                "AccessDom1", null);
        assertFalse(access.getGranted());

        access = zms.getAccess(rsrcCtx3, "CREATE", "AccessDom1:resource2",
                "AccessDom1", null);
        assertFalse(access.getGranted());

        // user2 and user3 have access to CREATE(*)/resource 3

        access = zms.getAccess(rsrcCtx1, "CREATE", "AccessDom1:resource3",
                "AccessDom1", null);
        assertFalse(access.getGranted());

        access = zms.getAccess(rsrcCtx2, "CREATE", "AccessDom1:resource3",
                "AccessDom1", null);
        assertTrue(access.getGranted());

        access = zms.getAccess(rsrcCtx3, "CREATE", "AccessDom1:resource3",
                "AccessDom1", null);
        assertTrue(access.getGranted());

        // user2 and user3 have access to UPDATE(*)/resource 3

        access = zms.getAccess(rsrcCtx1, "UPDATE", "AccessDom1:resource3",
                "AccessDom1", null);
        assertFalse(access.getGranted());

        access = zms.getAccess(rsrcCtx2, "UPDATE", "AccessDom1:resource3",
                "AccessDom1", null);
        assertTrue(access.getGranted());

        access = zms.getAccess(rsrcCtx3, "UPDATE", "AccessDom1:resource3",
                "AccessDom1", null);
        assertTrue(access.getGranted());

        // user2 and user3 have access to DELETE/resource 4 (*)

        access = zms.getAccess(rsrcCtx1, "DELETE", "AccessDom1:resource4",
                "AccessDom1", null);
        assertFalse(access.getGranted());

        access = zms.getAccess(rsrcCtx2, "DELETE", "AccessDom1:resource4",
                "AccessDom1", null);
        assertTrue(access.getGranted());

        access = zms.getAccess(rsrcCtx3, "DELETE", "AccessDom1:resource4",
                "AccessDom1", null);
        assertTrue(access.getGranted());

        // user1 should be able to read resource 5(*) but not resource 6
        // (explicit DENY)

        access = zms.getAccess(rsrcCtx1, "READ", "AccessDom1:resource5",
                "AccessDom1", null);
        assertTrue(access.getGranted());

        access = zms.getAccess(rsrcCtx1, "READ", "AccessDom1:resource6",
                "AccessDom1", null);
        assertFalse(access.getGranted());

        // we should get an exception since access is not allowed to be called
        // with user cookie - this api is only for functions that require a 
        // service or user tokens
 
        try {
            zms.access("READ", "AccessDom1:resource5", principal1, "AccessDom1");
        } catch (ResourceException ex) {
            assertEquals(400, ex.getCode());
        }

        zms.deleteTopLevelDomain(mockDomRsrcCtx, "AccessDom1", auditRef);
    }

    @Test
    public void testGetAccessCaseSensitive() {

        TopLevelDomain dom1 = createTopLevelDomainObject("AccessDom1",
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        Role role1 = createRoleObject("AccessDom1", "Role1", null, "user.user1",
                "user.user3");
        zms.putRole(mockDomRsrcCtx, "AccessDom1", "Role1", auditRef, role1);

        Role role2 = createRoleObject("AccessDom1", "Role2", null, "user.user2",
                "user.user3");
        zms.putRole(mockDomRsrcCtx, "AccessDom1", "Role2", auditRef, role2);

        Policy policy1 = createPolicyObject("AccessDom1", "Policy1", "Role1",
                "UpdatE", "AccessDom1:ResourcE1", AssertionEffect.ALLOW);
        policy1.setCaseSensitive(true);
        zms.putPolicy(mockDomRsrcCtx, "AccessDom1", "Policy1", auditRef, policy1);

        Policy policy2 = createPolicyObject("AccessDom1", "Policy2", "Role2",
                "CreatE", "AccessDom1:ResourcE2", AssertionEffect.DENY);
        policy2.setCaseSensitive(true);
        zms.putPolicy(mockDomRsrcCtx, "AccessDom1", "Policy2", auditRef, policy2);

        Policy policy3 = createPolicyObject("AccessDom1", "Policy3", "Role2",
                "*", "AccessDom1:ResourcE3", AssertionEffect.ALLOW);
        policy3.setCaseSensitive(true);
        zms.putPolicy(mockDomRsrcCtx, "AccessDom1", "Policy3", auditRef, policy3);

        Policy policy4 = createPolicyObject("AccessDom1", "Policy4", "Role2",
                "DeletE", "AccessdoM1:*", AssertionEffect.ALLOW);
        policy4.setCaseSensitive(true);
        zms.putPolicy(mockDomRsrcCtx, "AccessDom1", "Policy4", auditRef, policy4);

        Policy policy5 = createPolicyObject("AccessDom1", "Policy5", "Role1",
                "ReaD", "AccessdoM1:*", AssertionEffect.ALLOW);
        policy5.setCaseSensitive(true);
        zms.putPolicy(mockDomRsrcCtx, "AccessDom1", "Policy5", auditRef, policy5);

        Policy policy6 = createPolicyObject("AccessDom1", "Policy6", "Role1",
                "ReaD", "AccessDom1:ResourcE6", AssertionEffect.DENY);
        policy6.setCaseSensitive(true);
        zms.putPolicy(mockDomRsrcCtx, "AccessDom1", "Policy6", auditRef, policy6);

        // user1 and user3 have access to UPDATE/resource1

        Authority principalAuthority = new com.yahoo.athenz.common.server.debug.DebugPrincipalAuthority();
        Principal principal1 = principalAuthority.authenticate("v=U1;d=user;n=user1;s=signature",
                "10.11.12.13", "GET", null);
        ResourceContext rsrcCtx1 = createResourceContext(principal1);
        Principal principal2 = principalAuthority.authenticate("v=U1;d=user;n=user2;s=signature",
                "10.11.12.13", "GET", null);
        ResourceContext rsrcCtx2 = createResourceContext(principal2);
        Principal principal3 = principalAuthority.authenticate("v=U1;d=user;n=user3;s=signature",
                "10.11.12.13", "GET", null);
        ResourceContext rsrcCtx3 = createResourceContext(principal3);

        Access access = zms.getAccess(rsrcCtx1, "uPDATe", "AcceSSDom1:rESOURCe1",
                "AccessDom1", null);
        assertTrue(access.getGranted());

        access = zms.getAccess(rsrcCtx2, "UpDaTe", "AccEssDom1:reSouRce1",
                "AccessDom1", null);
        assertFalse(access.getGranted());

        access = zms.getAccess(rsrcCtx3, "uPdate", "AccEssDom1:resOurce1",
                "AccessDom1", null);
        assertTrue(access.getGranted());

        // same set as before with no trust domain field

        access = zms.getAccess(rsrcCtx1, "UPDaTE", "ACcessDom1:reSource1",
                null, null);
        assertTrue(access.getGranted());

        access = zms.getAccess(rsrcCtx2, "UPDAtE", "AccEssDom1:resOUrce1",
                null, null);
        assertFalse(access.getGranted());

        access = zms.getAccess(rsrcCtx3, "uPDATE", "ACcessDom1:resourcE1",
                null, null);
        assertTrue(access.getGranted());

        // all three have no access to CREATE action on resource1

        access = zms.getAccess(rsrcCtx1, "CREATE", "AccessDom1:resource1",
                "AccessDom1", null);
        assertFalse(access.getGranted());

        access = zms.getAccess(rsrcCtx2, "CREATE", "AccessDom1:resource1",
                "AccessDom1", null);
        assertFalse(access.getGranted());

        access = zms.getAccess(rsrcCtx3, "CREATE", "AccessDom1:resource1",
                "AccessDom1", null);
        assertFalse(access.getGranted());

        // all three have no access to invalid domain name on resource 1

        access = zms.getAccess(rsrcCtx1, "CREATE", "AccessDom1:resource1",
                "AccessDom2", null);
        assertFalse(access.getGranted());

        access = zms.getAccess(rsrcCtx2, "CREATE", "AccessDom1:resource1",
                "AccessDom2", null);
        assertFalse(access.getGranted());

        access = zms.getAccess(rsrcCtx3, "CREATE", "AccessDom1:resource1",
                "AccessDom2", null);
        assertFalse(access.getGranted());

        // same as before with no trust domain field

        access = zms.getAccess(rsrcCtx1, "CREATE", "AccessDom1:resource1",
                null, null);
        assertFalse(access.getGranted());

        access = zms.getAccess(rsrcCtx2, "CREATE", "AccessDom1:resource1",
                null, null);
        assertFalse(access.getGranted());

        access = zms.getAccess(rsrcCtx3, "CREATE", "AccessDom1:resource1",
                null, null);
        assertFalse(access.getGranted());

        // all three should have deny access to resource 2

        access = zms.getAccess(rsrcCtx1, "CREATE", "AccessDom1:resource2",
                "AccessDom1", null);
        assertFalse(access.getGranted());

        access = zms.getAccess(rsrcCtx2, "CREATE", "AccessDom1:resource2",
                "AccessDom1", null);
        assertFalse(access.getGranted());

        access = zms.getAccess(rsrcCtx3, "CREATE", "AccessDom1:resource2",
                "AccessDom1", null);
        assertFalse(access.getGranted());

        // user2 and user3 have access to CREATE(*)/resource 3

        access = zms.getAccess(rsrcCtx1, "CREATE", "AccessDom1:resource3",
                "AccessDom1", null);
        assertFalse(access.getGranted());

        access = zms.getAccess(rsrcCtx2, "CReATE", "AccessDOm1:resouRce3",
                "AccessDom1", null);
        assertTrue(access.getGranted());

        access = zms.getAccess(rsrcCtx3, "CREATe", "accessDom1:rEsource3",
                "AccessDom1", null);
        assertTrue(access.getGranted());

        // user2 and user3 have access to UPDATE(*)/resource 3

        access = zms.getAccess(rsrcCtx1, "UpDATE", "AcCessDom1:reSource3",
                "AccessDom1", null);
        assertFalse(access.getGranted());

        access = zms.getAccess(rsrcCtx2, "UPDaTE", "AccEssDom1:resourCe3",
                "AccessDom1", null);
        assertTrue(access.getGranted());

        access = zms.getAccess(rsrcCtx3, "UPDATe", "AccEssDom1:resouRce3",
                "AccessDom1", null);
        assertTrue(access.getGranted());

        // user2 and user3 have access to DELETE/resource 4 (*)

        access = zms.getAccess(rsrcCtx1, "DeLETE", "AccEssDOm1:resouRce4",
                "AccessDom1", null);
        assertFalse(access.getGranted());

        access = zms.getAccess(rsrcCtx2, "DELETe", "AccEssDom1:resouRce4",
                "AccessDom1", null);
        assertTrue(access.getGranted());

        access = zms.getAccess(rsrcCtx3, "DELEtE", "AccesSDom1:resouRce4",
                "AccessDom1", null);
        assertTrue(access.getGranted());

        // user1 should be able to read resource 5(*) but not resource 6
        // (explicit DENY)

        access = zms.getAccess(rsrcCtx1, "reaD", "ACCessDom1:reSource5",
                "AccessDom1", null);
        assertTrue(access.getGranted());

        access = zms.getAccess(rsrcCtx1, "READ", "AccessDom1:resource6",
                "AccessDom1", null);
        assertFalse(access.getGranted());

        // we should get an exception since access is not allowed to be called
        // with user cookie - this api is only for functions that require a
        // service or user tokens

        try {
            zms.access("READ", "AccessDom1:resource5", principal1, "AccessDom1");
        } catch (ResourceException ex) {
            assertEquals(400, ex.getCode());
        }

        zms.deleteTopLevelDomain(mockDomRsrcCtx, "AccessDom1", auditRef);
    }

    @Test
    public void testGetAccessWildcard() {

        TopLevelDomain dom1 = createTopLevelDomainObject("AccessDom1",
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        Role role1 = createRoleObject("AccessDom1", "Role1", null, "user.user1",
                "user.user3");
        zms.putRole(mockDomRsrcCtx, "AccessDom1", "Role1", auditRef, role1);

        Role role2 = createRoleObject("AccessDom1", "Role2", null, "user.user2",
                "user.user3");
        zms.putRole(mockDomRsrcCtx, "AccessDom1", "Role2", auditRef, role2);

        Policy policy1 = createPolicyObject("AccessDom1", "Policy1", "Role1",
                "UpdatE", "AccessDom1:ResourcE1", AssertionEffect.ALLOW);
        policy1.setCaseSensitive(true);
        zms.putPolicy(mockDomRsrcCtx, "AccessDom1", "Policy1", auditRef, policy1);

        Policy policy2 = createPolicyObject("AccessDom1", "Policy2", "Role2",
                "CreatE", "AccessDom1:ResourcE2", AssertionEffect.DENY);
        policy2.setCaseSensitive(true);
        zms.putPolicy(mockDomRsrcCtx, "AccessDom1", "Policy2", auditRef, policy2);

        Policy policy3 = createPolicyObject("AccessDom1", "Policy3", "Role2",
                "*", "AccessDom1:ResourcE3", AssertionEffect.ALLOW);
        policy3.setCaseSensitive(true);
        zms.putPolicy(mockDomRsrcCtx, "AccessDom1", "Policy3", auditRef, policy3);

        Policy policy4 = createPolicyObject("AccessDom1", "Policy4", "Role2",
                "DeletE", "AccessdoM1:*", AssertionEffect.ALLOW);
        policy4.setCaseSensitive(true);
        zms.putPolicy(mockDomRsrcCtx, "AccessDom1", "Policy4", auditRef, policy4);

        Policy policy5 = createPolicyObject("AccessDom1", "Policy5", "Role1",
                "ReaD", "AccessdoM1:*", AssertionEffect.ALLOW);
        policy5.setCaseSensitive(true);
        zms.putPolicy(mockDomRsrcCtx, "AccessDom1", "Policy5", auditRef, policy5);

        Policy policy6 = createPolicyObject("AccessDom1", "Policy6", "Role1",
                "ReaD", "AccessDom1:ResourcE6", AssertionEffect.DENY);
        policy6.setCaseSensitive(true);
        zms.putPolicy(mockDomRsrcCtx, "AccessDom1", "Policy6", auditRef, policy6);

        // user1 and user3 have access to UPDATE/resource1

        Authority principalAuthority = new com.yahoo.athenz.common.server.debug.DebugPrincipalAuthority();
        Principal principal1 = principalAuthority.authenticate("v=U1;d=user;n=user1;s=signature",
                "10.11.12.13", "GET", null);
        ResourceContext rsrcCtx1 = createResourceContext(principal1);
        Principal principal2 = principalAuthority.authenticate("v=U1;d=user;n=user2;s=signature",
                "10.11.12.13", "GET", null);
        ResourceContext rsrcCtx2 = createResourceContext(principal2);
        Principal principal3 = principalAuthority.authenticate("v=U1;d=user;n=user3;s=signature",
                "10.11.12.13", "GET", null);
        ResourceContext rsrcCtx3 = createResourceContext(principal3);

        Access access = zms.getAccess(rsrcCtx1, "uPDATe", "AcceSSDom1:rESOURCe1",
                "AccessDom1", null);
        assertTrue(access.getGranted());

        access = zms.getAccess(rsrcCtx2, "UpDaTe", "AccEssDom1:reSouRce1",
                "AccessDom1", null);
        assertFalse(access.getGranted());

        access = zms.getAccess(rsrcCtx3, "uPdate", "AccEssDom1:resOurce1",
                "AccessDom1", null);
        assertTrue(access.getGranted());

        // same set as before with no trust domain field

        access = zms.getAccess(rsrcCtx1, "UPDaTE", "ACcessDom1:reSource1",
                null, null);
        assertTrue(access.getGranted());

        access = zms.getAccess(rsrcCtx2, "UPDAtE", "AccEssDom1:resOUrce1",
                null, null);
        assertFalse(access.getGranted());

        access = zms.getAccess(rsrcCtx3, "uPDATE", "ACcessDom1:resourcE1",
                null, null);
        assertTrue(access.getGranted());

        // all three have no access to CREATE action on resource1

        access = zms.getAccess(rsrcCtx1, "CREATE", "AccessDom1:resource1",
                "AccessDom1", null);
        assertFalse(access.getGranted());

        access = zms.getAccess(rsrcCtx2, "CREATE", "AccessDom1:resource1",
                "AccessDom1", null);
        assertFalse(access.getGranted());

        access = zms.getAccess(rsrcCtx3, "CREATE", "AccessDom1:resource1",
                "AccessDom1", null);
        assertFalse(access.getGranted());

        // all three have no access to invalid domain name on resource 1

        access = zms.getAccess(rsrcCtx1, "CREATE", "AccessDom1:resource1",
                "AccessDom2", null);
        assertFalse(access.getGranted());

        access = zms.getAccess(rsrcCtx2, "CREATE", "AccessDom1:resource1",
                "AccessDom2", null);
        assertFalse(access.getGranted());

        access = zms.getAccess(rsrcCtx3, "CREATE", "AccessDom1:resource1",
                "AccessDom2", null);
        assertFalse(access.getGranted());

        // same as before with no trust domain field

        access = zms.getAccess(rsrcCtx1, "CREATE", "AccessDom1:resource1",
                null, null);
        assertFalse(access.getGranted());

        access = zms.getAccess(rsrcCtx2, "CREATE", "AccessDom1:resource1",
                null, null);
        assertFalse(access.getGranted());

        access = zms.getAccess(rsrcCtx3, "CREATE", "AccessDom1:resource1",
                null, null);
        assertFalse(access.getGranted());

        // all three should have deny access to resource 2

        access = zms.getAccess(rsrcCtx1, "CREATE", "AccessDom1:resource2",
                "AccessDom1", null);
        assertFalse(access.getGranted());

        access = zms.getAccess(rsrcCtx2, "CREATE", "AccessDom1:resource2",
                "AccessDom1", null);
        assertFalse(access.getGranted());

        access = zms.getAccess(rsrcCtx3, "CREATE", "AccessDom1:resource2",
                "AccessDom1", null);
        assertFalse(access.getGranted());

        // user2 and user3 have access to CREATE(*)/resource 3

        access = zms.getAccess(rsrcCtx1, "CREATE", "AccessDom1:resource3",
                "AccessDom1", null);
        assertFalse(access.getGranted());

        access = zms.getAccess(rsrcCtx2, "CReATE", "AccessDOm1:resouRce3",
                "AccessDom1", null);
        assertTrue(access.getGranted());

        access = zms.getAccess(rsrcCtx3, "CREATe", "accessDom1:rEsource3",
                "AccessDom1", null);
        assertTrue(access.getGranted());

        // user2 and user3 have access to UPDATE(*)/resource 3

        access = zms.getAccess(rsrcCtx1, "UpDATE", "AcCessDom1:reSource3",
                "AccessDom1", null);
        assertFalse(access.getGranted());

        access = zms.getAccess(rsrcCtx2, "UPDaTE", "AccEssDom1:resourCe3",
                "AccessDom1", null);
        assertTrue(access.getGranted());

        access = zms.getAccess(rsrcCtx3, "UPDATe", "AccEssDom1:resouRce3",
                "AccessDom1", null);
        assertTrue(access.getGranted());

        // user2 and user3 have access to DELETE/resource 4 (*)

        access = zms.getAccess(rsrcCtx1, "DeLETE", "AccEssDOm1:resouRce4",
                "AccessDom1", null);
        assertFalse(access.getGranted());

        access = zms.getAccess(rsrcCtx2, "DELETe", "AccEssDom1:resouRce4",
                "AccessDom1", null);
        assertTrue(access.getGranted());

        access = zms.getAccess(rsrcCtx3, "DELEtE", "AccesSDom1:resouRce4",
                "AccessDom1", null);
        assertTrue(access.getGranted());

        // user1 should be able to read resource 5(*) but not resource 6
        // (explicit DENY)

        access = zms.getAccess(rsrcCtx1, "reaD", "ACCessDom1:reSource5",
                "AccessDom1", null);
        assertTrue(access.getGranted());

        access = zms.getAccess(rsrcCtx1, "READ", "AccessDom1:resource6",
                "AccessDom1", null);
        assertFalse(access.getGranted());

        // we should get an exception since access is not allowed to be called
        // with user cookie - this api is only for functions that require a
        // service or user tokens

        try {
            zms.access("READ", "AccessDom1:resource5", principal1, "AccessDom1");
        } catch (ResourceException ex) {
            assertEquals(400, ex.getCode());
        }

        zms.deleteTopLevelDomain(mockDomRsrcCtx, "AccessDom1", auditRef);
    }
    
    @Test
    public void testGetAccessCrossUser() {

        TopLevelDomain dom1 = createTopLevelDomainObject("CrossAllowDom1",
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        Role role1 = createRoleObject("CrossAllowDom1", "Role1", null,
                "user.user1", "user.user3");
        zms.putRole(mockDomRsrcCtx, "CrossAllowDom1", "Role1", auditRef, role1);

        Role role2 = createRoleObject("CrossAllowDom1", "Role2", null,
                "user.user2", "user.user3");
        zms.putRole(mockDomRsrcCtx, "CrossAllowDom1", "Role2", auditRef, role2);

        Role role3 = createRoleObject("CrossAllowDom1", "Role3", null,
                "user.user1", null);
        zms.putRole(mockDomRsrcCtx, "CrossAllowDom1", "Role3", auditRef, role3);

        Policy policy1 = createPolicyObject("CrossAllowDom1", "Policy1",
                "Role1", "UPDATE", "CrossAllowDom1:resource1",
                AssertionEffect.ALLOW);
        zms.putPolicy(mockDomRsrcCtx, "CrossAllowDom1", "Policy1", auditRef, policy1);

        Policy policy2 = createPolicyObject("CrossAllowDom1", "Policy2",
                "Role2", "CREATE", "CrossAllowDom1:resource2",
                AssertionEffect.DENY);
        zms.putPolicy(mockDomRsrcCtx, "CrossAllowDom1", "Policy2", auditRef, policy2);

        Policy policy3 = createPolicyObject("CrossAllowDom1", "Policy3",
                "Role2", "*", "CrossAllowDom1:resource3", AssertionEffect.ALLOW);
        zms.putPolicy(mockDomRsrcCtx, "CrossAllowDom1", "Policy3", auditRef, policy3);

        Policy policy4 = createPolicyObject("CrossAllowDom1", "Policy4",
                "Role2", "DELETE", "CrossAllowDom1:*", AssertionEffect.ALLOW);
        zms.putPolicy(mockDomRsrcCtx, "CrossAllowDom1", "Policy4", auditRef, policy4);

        // verify we have allow access for access resource

        Authority principalAuthority = new com.yahoo.athenz.common.server.debug.DebugPrincipalAuthority();
        Principal principal1 = principalAuthority.authenticate("v=U1;d=user;n=user1;s=signature",
                "10.11.12.13", "GET", null);
        ResourceContext rsrcCtx1 = createResourceContext(principal1);
        Principal principal2 = principalAuthority.authenticate("v=U1;d=user;n=user2;s=signature",
                "10.11.12.13", "GET", null);
        ResourceContext rsrcCtx2 = createResourceContext(principal2);
        Principal principal3 = principalAuthority.authenticate("v=U1;d=user;n=user3;s=signature",
                "10.11.12.13", "GET", null);
        ResourceContext rsrcCtx3 = createResourceContext(principal3);

        // user1 and user3 have access to UPDATE/resource1

        Access access = zms.getAccess(rsrcCtx1, "UPDATE", "CrossAllowDom1:resource1",
                "CrossAllowDom1", null);
        assertTrue(access.getGranted());

        access = zms.getAccess(rsrcCtx1, "UPDATE", "CrossAllowDom1:resource1",
                "CrossAllowDom1", "user1");
        assertTrue(access.getGranted());

        access = zms.getAccess(rsrcCtx1, "UPDATE", "CrossAllowDom1:resource1",
                "CrossAllowDom1", "user.user1");
        assertTrue(access.getGranted());

        access = zms.getAccess(rsrcCtx2, "UPDATE", "CrossAllowDom1:resource1",
                "CrossAllowDom1", null);
        assertFalse(access.getGranted());

        access = zms.getAccess(rsrcCtx1, "UPDATE", "CrossAllowDom1:resource1",
                "CrossAllowDom1", "user2");
        assertFalse(access.getGranted());

        access = zms.getAccess(rsrcCtx1, "UPDATE", "CrossAllowDom1:resource1",
                "CrossAllowDom1", "user.user2");
        assertFalse(access.getGranted());

        access = zms.getAccess(rsrcCtx3, "UPDATE", "CrossAllowDom1:resource1",
                "CrossAllowDom1", null);
        assertTrue(access.getGranted());

        access = zms.getAccess(rsrcCtx1, "UPDATE", "CrossAllowDom1:resource1",
                "CrossAllowDom1", "user3");
        assertTrue(access.getGranted());

        access = zms.getAccess(rsrcCtx1, "UPDATE", "CrossAllowDom1:resource1",
                "CrossAllowDom1", "user.user3");
        assertTrue(access.getGranted());

        // all three have no access to CREATE action on resource1

        access = zms.getAccess(rsrcCtx1, "CREATE", "CrossAllowDom1:resource1",
                "CrossAllowDom1", null);
        assertFalse(access.getGranted());

        access = zms.getAccess(rsrcCtx1, "CREATE", "CrossAllowDom1:resource1",
                "CrossAllowDom1", "user1");
        assertFalse(access.getGranted());

        access = zms.getAccess(rsrcCtx1, "CREATE", "CrossAllowDom1:resource1",
                "CrossAllowDom1", "user.user1");
        assertFalse(access.getGranted());

        access = zms.getAccess(rsrcCtx2, "CREATE", "CrossAllowDom1:resource1",
                "CrossAllowDom1", null);
        assertFalse(access.getGranted());

        access = zms.getAccess(rsrcCtx1, "CREATE", "CrossAllowDom1:resource1",
                "CrossAllowDom1", "user2");
        assertFalse(access.getGranted());

        access = zms.getAccess(rsrcCtx1, "CREATE", "CrossAllowDom1:resource1",
                "CrossAllowDom1", "user.user2");
        assertFalse(access.getGranted());

        access = zms.getAccess(rsrcCtx3, "CREATE", "CrossAllowDom1:resource1",
                "CrossAllowDom1", null);
        assertFalse(access.getGranted());

        access = zms.getAccess(rsrcCtx1, "CREATE", "CrossAllowDom1:resource1",
                "CrossAllowDom1", "user3");
        assertFalse(access.getGranted());

        access = zms.getAccess(rsrcCtx1, "CREATE", "CrossAllowDom1:resource1",
                "CrossAllowDom1", "user.user3");
        assertFalse(access.getGranted());

        // all three have no access to invalid domain name on resource 1

        access = zms.getAccess(rsrcCtx1, "CREATE", "CrossAllowDom1:resource1",
                "CrossAllowDom2", null);
        assertFalse(access.getGranted());

        access = zms.getAccess(rsrcCtx1, "CREATE", "CrossAllowDom1:resource1",
                "CrossAllowDom2", "user1");
        assertFalse(access.getGranted());

        access = zms.getAccess(rsrcCtx1, "CREATE", "CrossAllowDom1:resource1",
                "CrossAllowDom2", "user.user1");
        assertFalse(access.getGranted());

        access = zms.getAccess(rsrcCtx2, "CREATE", "CrossAllowDom1:resource1",
                "CrossAllowDom2", null);
        assertFalse(access.getGranted());

        // user2 and user3 have access to CREATE(*)/resource 3

        access = zms.getAccess(rsrcCtx1, "CREATE", "CrossAllowDom1:resource3",
                "CrossAllowDom1", null);
        assertFalse(access.getGranted());

        access = zms.getAccess(rsrcCtx1, "CREATE", "CrossAllowDom1:resource3",
                "CrossAllowDom1", "user1");
        assertFalse(access.getGranted());

        access = zms.getAccess(rsrcCtx1, "CREATE", "CrossAllowDom1:resource3",
                "CrossAllowDom1", "user.user1");
        assertFalse(access.getGranted());

        access = zms.getAccess(rsrcCtx2, "CREATE", "CrossAllowDom1:resource3",
                "CrossAllowDom1", null);
        assertTrue(access.getGranted());

        access = zms.getAccess(rsrcCtx1, "CREATE", "CrossAllowDom1:resource3",
                "CrossAllowDom1", "user2");
        assertTrue(access.getGranted());

        access = zms.getAccess(rsrcCtx1, "CREATE", "CrossAllowDom1:resource3",
                "CrossAllowDom1", "user.user2");
        assertTrue(access.getGranted());

        access = zms.getAccess(rsrcCtx3, "CREATE", "CrossAllowDom1:resource3",
                "CrossAllowDom1", null);
        assertTrue(access.getGranted());

        access = zms.getAccess(rsrcCtx1, "CREATE", "CrossAllowDom1:resource3",
                "CrossAllowDom1", "user3");
        assertTrue(access.getGranted());

        access = zms.getAccess(rsrcCtx1, "CREATE", "CrossAllowDom1:resource3",
                "CrossAllowDom1", "user.user3");
        assertTrue(access.getGranted());

        // user2 and user3 are allowed to check each other's access

        access = zms.getAccess(rsrcCtx2, "UPDATE", "CrossAllowDom1:resource1",
                "CrossAllowDom1", "user1");
        assertTrue(access.getGranted());

        access = zms.getAccess(rsrcCtx2, "UPDATE", "CrossAllowDom1:resource1",
                "CrossAllowDom1", "user.user1");
        assertTrue(access.getGranted());

        access = zms.getAccess(rsrcCtx3, "UPDATE", "CrossAllowDom1:resource1",
                "CrossAllowDom1", "user1");
        assertTrue(access.getGranted());

        access = zms.getAccess(rsrcCtx3, "UPDATE", "CrossAllowDom1:resource1",
                "CrossAllowDom1", "user.user1");
        assertTrue(access.getGranted());

        zms.deleteTopLevelDomain(mockDomRsrcCtx, "CrossAllowDom1", auditRef);
    }

    @Test
    public void testGetAccessHomeDomainEnabled() {
        
        System.setProperty(ZMSConsts.ZMS_PROP_VIRTUAL_DOMAIN, "true");
        ZMSImpl zmsTest = zmsInit();

        Authority principalAuthority = new com.yahoo.athenz.common.server.debug.DebugPrincipalAuthority();

        Principal pJane = principalAuthority.authenticate("v=U1;d=user;n=jane;s=signature",
                "10.11.12.13", "GET", null);
        ResourceContext rsrcCtxJane = createResourceContext(pJane);
        
        Access access = zmsTest.getAccess(rsrcCtxJane, "READ", "user.jane:Resource1", null, null);
        assertTrue(access.getGranted());
        
        access = zmsTest.getAccess(rsrcCtxJane, "WRITE", "user.jane:Resource1", null, null);
        assertTrue(access.getGranted());

        access = zmsTest.getAccess(rsrcCtxJane, "UPDATE", "user.jane:Resource1", null, null);
        assertTrue(access.getGranted());

        // user id does not match domain - all should be failure
        
        Principal pJohn = principalAuthority.authenticate("v=U1;d=user;n=john;s=signature",
                "10.11.12.13", "GET", null);
        ResourceContext rsrcCtxJohn = createResourceContext(pJohn);
        
        try {
            zmsTest.getAccess(rsrcCtxJohn, "READ", "user.jane:Resource1", null, null);
            fail();
        } catch (ResourceException ex) {
            assertEquals(404, ex.getCode());
        }
        
        try {
            zmsTest.getAccess(rsrcCtxJohn, "WRITE", "user.jane:Resource1", null, null);
            fail();
        } catch (ResourceException ex) {
            assertEquals(404, ex.getCode());
        }

        try {
            zmsTest.getAccess(rsrcCtxJohn, "UPDATE", "user.jane:Resource1", null, null);
            fail();
        } catch (ResourceException ex) {
            assertEquals(404, ex.getCode());
        }
        
        System.clearProperty(ZMSConsts.ZMS_PROP_VIRTUAL_DOMAIN);
    }

    @Test
    public void testGetAccessHomeDomainDisabled() {
        
        System.setProperty(ZMSConsts.ZMS_PROP_VIRTUAL_DOMAIN, "false");
        ZMSImpl zmsTest = zmsInit();

        Authority principalAuthority = new com.yahoo.athenz.common.server.debug.DebugPrincipalAuthority();

        Principal pJane = principalAuthority.authenticate("v=U1;d=user;n=jane;s=signature",
                "10.11.12.13", "GET", null);
        ResourceContext rsrcCtxJane = createResourceContext(pJane);

        try {
            zmsTest.getAccess(rsrcCtxJane, "READ", "user.jane:Resource1", null, null);
            fail();
        } catch (ResourceException ex) {
            assertEquals(404, ex.getCode());
        }
        
        try {
            zmsTest.getAccess(rsrcCtxJane, "WRITE", "user.jane:Resource1", null, null);
            fail();
        } catch (ResourceException ex) {
            assertEquals(404, ex.getCode());
        }
        
        try {
            zmsTest.getAccess(rsrcCtxJane, "UPDATE", "user.jane:Resource1", null, null);
            fail();
        } catch (ResourceException ex) {
            assertEquals(404, ex.getCode());
        }
        
        System.clearProperty(ZMSConsts.ZMS_PROP_VIRTUAL_DOMAIN);
    }

    @Test
    public void testGetAccessFailures() {

        final String domainName = "access-domain-fails";

        Authority authority = Mockito.mock(Authority.class);
        Mockito.when(authority.allowAuthorization()).thenReturn(false);
        Principal principal1 = Mockito.mock(Principal.class);
        Mockito.when(principal1.getAuthority()).thenReturn(authority);

        // authority not authorized

        assertFalse(zms.access("update", domainName + ":resource1", principal1, null));

        Authority principalAuthority = new com.yahoo.athenz.common.server.debug.DebugPrincipalAuthority();
        Principal principal2 = principalAuthority.authenticate("v=U1;d=user;n=user2;s=signature",
                "10.11.12.13", "GET", null);

        // domain name missing in resource

        try {
            zms.access("update", "resource1", principal2, null);
            fail();
        } catch (com.yahoo.athenz.common.server.rest.ResourceException ex) {
            assertEquals(ex.getCode(), 404);
        }

        RsrcCtxWrapper ctx = Mockito.mock(RsrcCtxWrapper.class);
        try {
            zms.getAccessCheck(principal2, "update", "resource1", null, null, ctx);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 404);
        }

        // domain is disabled

        Domain dom1 = new Domain().setName(domainName).setEnabled(false);

        List<String> adminUsers = new ArrayList<>();
        adminUsers.add(adminUser);
        zms.dbService.makeDomain(mockDomRsrcCtx, dom1, adminUsers, null, auditRef);

        try {
            zms.access("update", domainName + ":resource1", principal2, null);
            fail();
        } catch (com.yahoo.athenz.common.server.rest.ResourceException ex) {
            assertEquals(ex.getCode(), 403);
        }

        try {
            zms.getAccessCheck(principal2, "update", domainName + ":resource1", null, null, ctx);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 403);
        }

        zms.deleteTopLevelDomain(mockDomRsrcCtx, domainName, auditRef);
    }

    @Test
    public void testRetrieveAccessDomainValid() {
        
        TopLevelDomain dom1 = createTopLevelDomainObject("AccessDomain",
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);
        
        Authority principalAuthority = new com.yahoo.athenz.common.server.debug.DebugPrincipalAuthority();
        Principal pJane = principalAuthority.authenticate("v=U1;d=user;n=jane;s=signature",
                "10.11.12.13", "GET", null);
        
        AthenzDomain athenzDomain = zms.retrieveAccessDomain("accessdomain", pJane);
        assertNotNull(athenzDomain);

        zms.deleteTopLevelDomain(mockDomRsrcCtx, "AccessDomain", auditRef);
    }

    @Test
    public void testRetrieveAccessDomainVirtualValid() {
        
        System.setProperty(ZMSConsts.ZMS_PROP_VIRTUAL_DOMAIN, "true");
        ZMSImpl zmsTest = zmsInit();

        Authority principalAuthority = new com.yahoo.athenz.common.server.debug.DebugPrincipalAuthority();
        Principal principal = SimplePrincipal.create("user", "user1", "v=U1;d=user;n=user1;s=signature",
                0, principalAuthority);
        
        AthenzDomain athenzDomain = zmsTest.retrieveAccessDomain("user.user1", principal);
        assertNotNull(athenzDomain);
        assertEquals(athenzDomain.getName(), "user.user1");
        
        System.clearProperty(ZMSConsts.ZMS_PROP_VIRTUAL_DOMAIN);
    }

    @Test
    public void testRetrieveAccessDomainVirtualDomainDisabled() {
        
        System.setProperty(ZMSConsts.ZMS_PROP_VIRTUAL_DOMAIN, "false");
        ZMSImpl zmsTest = zmsInit();

        Authority principalAuthority = new com.yahoo.athenz.common.server.debug.DebugPrincipalAuthority();
        Principal principal = SimplePrincipal.create("user", "user1", "v=U1;d=user;n=user1;s=signature",
                0, principalAuthority);
        
        AthenzDomain athenzDomain = zmsTest.retrieveAccessDomain("user.user1", principal);
        assertNull(athenzDomain);
        
        System.clearProperty(ZMSConsts.ZMS_PROP_VIRTUAL_DOMAIN);
    }

    @Test
    public void testRetrieveAccessDomainPrincialNullDomain() {
        
        System.setProperty(ZMSConsts.ZMS_PROP_VIRTUAL_DOMAIN, "true");
        ZMSImpl zmsTest = zmsInit();

        Authority principalAuthority = new com.yahoo.athenz.common.server.debug.DebugPrincipalAuthority();
        Principal principal = SimplePrincipal.create("user1", "v=U1;d=user;n=user1;s=signature",
                principalAuthority);

        AthenzDomain athenzDomain = zmsTest.retrieveAccessDomain("user.user1", principal);
        assertNull(athenzDomain);
        
        System.clearProperty(ZMSConsts.ZMS_PROP_VIRTUAL_DOMAIN);
        
    }

    @Test
    public void testRetrieveAccessDomainMismatch() {
        
        System.setProperty(ZMSConsts.ZMS_PROP_VIRTUAL_DOMAIN, "true");
        ZMSImpl zmsTest = zmsInit();

        Authority principalAuthority = new com.yahoo.athenz.common.server.debug.DebugPrincipalAuthority();
        Principal principal = SimplePrincipal.create("user", "user2", "v=U1;d=user;n=user2;s=signature",
                0, principalAuthority);
        
        AthenzDomain athenzDomain = zmsTest.retrieveAccessDomain("user.user1", principal);
        assertNull(athenzDomain);
        
        System.clearProperty(ZMSConsts.ZMS_PROP_VIRTUAL_DOMAIN);
    }
    
    @Test
    public void testGetAccessCrossDomain() {

        setupTenantDomainProviderService("CrossDomainAccessDom1", "coretech", "storage",
                "http://localhost:8090/provider");

        Tenancy tenant = createTenantObject("CrossDomainAccessDom1", "coretech.storage");
        zms.putTenancy(mockDomRsrcCtx, "CrossDomainAccessDom1", "coretech.storage", auditRef, tenant);

        List<TenantRoleAction> roleActions = new ArrayList<>();
        for (Struct.Field f : TABLE_PROVIDER_ROLE_ACTIONS) {
            roleActions.add(new TenantRoleAction().setRole(f.name()).setAction((String) f.value()));
        }
        TenantResourceGroupRoles tenantRoles = new TenantResourceGroupRoles().setDomain("coretech")
                .setService("storage").setTenant("CrossDomainAccessDom1")
                .setRoles(roleActions).setResourceGroup("group1");

        zms.putTenantResourceGroupRoles(mockDomRsrcCtx, "coretech", "storage", "CrossDomainAccessDom1",
                "group1", auditRef, tenantRoles);

        // reset roles in the CrossDomainAccessDom1 domain with unique values

        Role role = createRoleObject("CrossDomainAccessDom1", "reader", null, "user.joe",
                "user.jane");
        zms.putRole(mockDomRsrcCtx, "CrossDomainAccessDom1", "reader", auditRef, role);

        role = createRoleObject("CrossDomainAccessDom1", "writer", null, "user.john",
                "user.jane");
        zms.putRole(mockDomRsrcCtx, "CrossDomainAccessDom1", "writer", auditRef, role);

        Policy policy = createPolicyObject("CrossDomainAccessDom1", "tenancy.coretech.storage.writer",
                "writer", "ASSUME_ROLE",
                "coretech:role.storage.tenant.CrossDomainAccessDom1.res_group.group1.writer",
                AssertionEffect.ALLOW);
        zms.putPolicy(mockDomRsrcCtx, "CrossDomainAccessDom1", "tenancy.coretech.storage.writer",
                auditRef, policy);

        policy = createPolicyObject("CrossDomainAccessDom1", "tenancy.coretech.storage.reader",
                "reader", "ASSUME_ROLE",
                "coretech:role.storage.tenant.CrossDomainAccessDom1.res_group.group1.reader",
                AssertionEffect.ALLOW);
        zms.putPolicy(mockDomRsrcCtx, "CrossDomainAccessDom1", "tenancy.coretech.storage.reader",
                auditRef, policy);

        // verify the ASSUME_ROLE check - with trust domain specified it should work and
        // without trust domain it will not work since the resource is pointing to the
        // provider's domain and not to the tenant's domain
        
        Access access = zms.getAccess(mockDomRsrcCtx, "ASSUME_ROLE",
                "coretech:role.storage.tenant.CrossDomainAccessDom1.res_group.group1.reader",
                null, "user.jane");
        assertFalse(access.getGranted());
        
        access = zms.getAccess(mockDomRsrcCtx, "ASSUME_ROLE",
                "coretech:role.storage.tenant.CrossDomainAccessDom1.res_group.group1.reader",
                "CrossDomainAccessDom1", "user.jane");
        assertTrue(access.getGranted());
        
        Authority principalAuthority = new com.yahoo.athenz.common.server.debug.DebugPrincipalAuthority();

        Principal pJane = principalAuthority.authenticate("v=U1;d=user;n=jane;s=signature",
                "10.11.12.13", "GET", null);
        ResourceContext rsrcCtxJane = createResourceContext(pJane);
        Principal pJohn = principalAuthority.authenticate("v=U1;d=user;n=john;s=signature",
                "10.11.12.13", "GET", null);
        ResourceContext rsrcCtxJohn = createResourceContext(pJohn);
        Principal pJoe = principalAuthority.authenticate("v=U1;d=user;n=joe;s=signature",
                "10.11.12.13", "GET", null);
        ResourceContext rsrcCtxJoe = createResourceContext(pJoe);

        access = zms.getAccess(rsrcCtxJoe, "READ",
                "coretech:service.storage.tenant.CrossDomainAccessDom1.res_group.group1.resource1",
                "CrossDomainAccessDom1", null);
        assertTrue(access.getGranted());

        access = zms.getAccess(rsrcCtxJane, "READ",
                "coretech:service.storage.tenant.CrossDomainAccessDom1.res_group.group1.resource1",
                "CrossDomainAccessDom1", null);
        assertTrue(access.getGranted());

        access = zms.getAccess(rsrcCtxJohn, "READ",
                "coretech:service.storage.tenant.CrossDomainAccessDom1.res_group.group1.resource1",
                "CrossDomainAccessDom1", null);
        assertFalse(access.getGranted());

        access = zms.getAccess(rsrcCtxJoe, "WRITE",
                "coretech:service.storage.tenant.CrossDomainAccessDom1.res_group.group1.resource1",
                "CrossDomainAccessDom1", null);
        assertFalse(access.getGranted());

        access = zms.getAccess(rsrcCtxJane, "WRITE",
                "coretech:service.storage.tenant.CrossDomainAccessDom1.res_group.group1.resource1",
                "CrossDomainAccessDom1", null);
        assertTrue(access.getGranted());

        access = zms.getAccess(rsrcCtxJohn, "WRITE",
                "coretech:service.storage.tenant.CrossDomainAccessDom1.res_group.group1.resource1",
                "CrossDomainAccessDom1", null);
        assertTrue(access.getGranted());

        // unknown action should always fail

        access = zms.getAccess(rsrcCtxJoe, "UPDATE",
                "coretech:service.storage.tenant.CrossDomainAccessDom1.res_group.group1.resource1",
                "CrossDomainAccessDom1", null);
        assertFalse(access.getGranted());

        access = zms.getAccess(rsrcCtxJane, "UPDATE",
                "coretech:service.storage.tenant.CrossDomainAccessDom1.res_group.group1.resource1",
                "CrossDomainAccessDom1", null);
        assertFalse(access.getGranted());

        access = zms.getAccess(rsrcCtxJohn, "UPDATE",
                "coretech:service.storage.tenant.CrossDomainAccessDom1.res_group.group1.resource1",
                "CrossDomainAccessDom1", null);
        assertFalse(access.getGranted());

        // same set as above without trust domain field

        access = zms.getAccess(rsrcCtxJoe, "READ",
                "coretech:service.storage.tenant.CrossDomainAccessDom1.res_group.group1.resource1",
                null, null);
        assertTrue(access.getGranted());

        access = zms.getAccess(rsrcCtxJane, "READ",
                "coretech:service.storage.tenant.CrossDomainAccessDom1.res_group.group1.resource1",
                null, null);
        assertTrue(access.getGranted());

        access = zms.getAccess(rsrcCtxJohn, "READ",
                "coretech:service.storage.tenant.CrossDomainAccessDom1.res_group.group1.resource1",
                null, null);
        assertFalse(access.getGranted());

        access = zms.getAccess(rsrcCtxJoe, "WRITE",
                "coretech:service.storage.tenant.CrossDomainAccessDom1.res_group.group1.resource1",
                null, null);
        assertFalse(access.getGranted());

        access = zms.getAccess(rsrcCtxJane, "WRITE",
                "coretech:service.storage.tenant.CrossDomainAccessDom1.res_group.group1.resource1",
                null, null);
        assertTrue(access.getGranted());

        access = zms.getAccess(rsrcCtxJohn, "WRITE",
                "coretech:service.storage.tenant.CrossDomainAccessDom1.res_group.group1.resource1",
                null, null);
        assertTrue(access.getGranted());

        // failure with different domain name

        access = zms.getAccess(rsrcCtxJoe, "READ",
                "coretech:service.storage.tenant.CrossDomainAccessDom1.res_group.group1.resource1",
                "CrossDomainAccessDom2", null);
        assertFalse(access.getGranted());

        access = zms.getAccess(rsrcCtxJane, "READ",
                "coretech:service.storage.tenant.CrossDomainAccessDom1.res_group.group1.resource1",
                "CrossDomainAccessDom2", null);
        assertFalse(access.getGranted());

        access = zms.getAccess(rsrcCtxJohn, "READ",
                "coretech:service.storage.tenant.CrossDomainAccessDom1.res_group.group1.resource1",
                "CrossDomainAccessDom2", null);
        assertFalse(access.getGranted());

        zms.deleteTenancy(mockDomRsrcCtx, "CrossDomainAccessDom1", "coretech.storage", auditRef);

        zms.deleteTopLevelDomain(mockDomRsrcCtx, "CrossDomainAccessDom1", auditRef);
        zms.deleteTopLevelDomain(mockDomRsrcCtx, "coretech", auditRef);
    }

    @Test
    public void testGetAccessCrossDomainWildCardResources() {

        // create the netops domain
        
        TopLevelDomain dom = createTopLevelDomainObject("netops",
                "Test Netops", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom);

        Role role = createRoleObject("netops", "users", null, null, null);
        zms.putRole(mockDomRsrcCtx, "netops", "users", auditRef, role);
        
        role = createRoleObject("netops", "superusers", null, "user.siteops_user_1",
                "user.siteops_user_2");
        zms.putRole(mockDomRsrcCtx, "netops", "superusers", auditRef, role);
        
        Policy policy = createPolicyObject("netops", "users",
                "users", "NODE_USER", "netops:node.",
                AssertionEffect.ALLOW);
        zms.putPolicy(mockDomRsrcCtx, "netops", "users", auditRef, policy);

        policy = createPolicyObject("netops", "superusers",
                "superusers", "NODE_SUDO", "netops:node.",
                AssertionEffect.ALLOW);
        zms.putPolicy(mockDomRsrcCtx, "netops", "superusers", auditRef, policy);

        policy = createPolicyObject("netops", "netops_superusers",
                "netops:role.superusers", false, "ASSUME_ROLE", "*:role.netops_superusers",
                AssertionEffect.ALLOW);
        zms.putPolicy(mockDomRsrcCtx, "netops", "netops_superusers", auditRef, policy);

        // create the weather domain
        
        dom = createTopLevelDomainObject("weather",
                "Test weather", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom);

        role = createRoleObject("weather", "users", null, null, null);
        zms.putRole(mockDomRsrcCtx, "weather", "users", auditRef, role);
        
        role = createRoleObject("weather", "superusers", null, "user.weather_admin_user",
                null);
        zms.putRole(mockDomRsrcCtx, "weather", "superusers", auditRef, role);

        role = createRoleObject("weather", "netops_superusers", "netops");
        zms.putRole(mockDomRsrcCtx, "weather", "netops_superusers", auditRef, role);
        
        policy = createPolicyObject("weather", "users",
                "users", "NODE_USER", "weather:node.",
                AssertionEffect.ALLOW);
        zms.putPolicy(mockDomRsrcCtx, "weather", "users", auditRef, policy);

        policy = createPolicyObject("weather", "superusers",
                "superusers", "NODE_SUDO", "weather:node.*",
                AssertionEffect.ALLOW);
        zms.putPolicy(mockDomRsrcCtx, "weather", "superusers", auditRef, policy);

        policy = createPolicyObject("weather", "netops_superusers",
                "netops_superusers", "NODE_SUDO", "weather:node.*",
                AssertionEffect.ALLOW);
        zms.putPolicy(mockDomRsrcCtx, "weather", "netops_superusers", auditRef, policy);

        Authority principalAuthority = new com.yahoo.athenz.common.server.debug.DebugPrincipalAuthority();

        Principal pWeather = principalAuthority.authenticate("v=U1;d=user;n=weather_admin_user;s=signature",
                "10.11.12.13", "GET", null);
        ResourceContext rsrcCtxWeather = createResourceContext(pWeather);

        Access access = zms.getAccess(rsrcCtxWeather, "NODE_SUDO", "weather:node.x", null, null);
        assertTrue(access.getGranted());
        
        Principal pSiteOps = principalAuthority.authenticate("v=U1;d=user;n=siteops_user_1;s=signature",
                "10.11.12.13", "GET", null);
        ResourceContext rsrcCtxSiteOps = createResourceContext(pSiteOps);

        access = zms.getAccess(rsrcCtxSiteOps, "NODE_SUDO", "weather:node.x", null, null);
        assertTrue(access.getGranted());
        
        Principal pRandom = principalAuthority.authenticate("v=U1;d=user;n=random_user;s=signature",
                "10.11.12.13", "GET", null);
        ResourceContext rsrcCtxRandom = createResourceContext(pRandom);

        access = zms.getAccess(rsrcCtxRandom, "NODE_SUDO", "weather:node.x", null, null);
        assertFalse(access.getGranted());
        
        zms.deleteTopLevelDomain(mockDomRsrcCtx, "weather", auditRef);
        zms.deleteTopLevelDomain(mockDomRsrcCtx, "netops", auditRef);
    }

    @Test
    public void testGetAccessExt() {

        final String testDomainName = "AccessDomExt1";
        
        TopLevelDomain dom1 = createTopLevelDomainObject(testDomainName,
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        Role role1 = createRoleObject(testDomainName, "Role1", null, "user.user1",
                "user.user3");
        zms.putRole(mockDomRsrcCtx, testDomainName, "Role1", auditRef, role1);

        Role role2 = createRoleObject(testDomainName, "Role2", null, "user.user2",
                "user.user3");
        zms.putRole(mockDomRsrcCtx, testDomainName, "Role2", auditRef, role2);

        Policy policy1 = createPolicyObject(testDomainName, "Policy1", "Role1",
                "UPDATE", testDomainName + ":resource1/resource2", AssertionEffect.ALLOW);
        zms.putPolicy(mockDomRsrcCtx, testDomainName, "Policy1", auditRef, policy1);

        Policy policy2 = createPolicyObject(testDomainName, "Policy2", "Role2",
                "CREATE", testDomainName + ":resource2(resource3)", AssertionEffect.ALLOW);
        zms.putPolicy(mockDomRsrcCtx, testDomainName, "Policy2", auditRef, policy2);

        Policy policy3 = createPolicyObject(testDomainName, "Policy3", "Role2",
                "*", testDomainName + ":resource3/*", AssertionEffect.ALLOW);
        zms.putPolicy(mockDomRsrcCtx, testDomainName, "Policy3", auditRef, policy3);

        Policy policy4 = createPolicyObject(testDomainName, "Policy4", "Role1",
                "READ", testDomainName + ":resource4[*]/data1", AssertionEffect.ALLOW);
        zms.putPolicy(mockDomRsrcCtx, testDomainName, "Policy4", auditRef, policy4);

        Policy policy5 = createPolicyObject(testDomainName, "Policy5", "Role2",
                "access", testDomainName + ":https://*.athenz.com/*", AssertionEffect.ALLOW);
        zms.putPolicy(mockDomRsrcCtx, testDomainName, "Policy5", auditRef, policy5);
        
        // user1 and user3 have access to UPDATE/resource1

        Authority principalAuthority = new com.yahoo.athenz.common.server.debug.DebugPrincipalAuthority();
        Principal principal1 = principalAuthority.authenticate("v=U1;d=user;n=user1;s=signature",
                "10.11.12.13", "GET", null);
        ResourceContext rsrcCtx1 = createResourceContext(principal1);
        Principal principal2 = principalAuthority.authenticate("v=U1;d=user;n=user2;s=signature",
                "10.11.12.13", "GET", null);
        ResourceContext rsrcCtx2 = createResourceContext(principal2);
        Principal principal3 = principalAuthority.authenticate("v=U1;d=user;n=user3;s=signature",
                "10.11.12.13", "GET", null);
        ResourceContext rsrcCtx3 = createResourceContext(principal3);

        // user1 and user3 have update access to resource1/resource2
        
        Access access = zms.getAccessExt(rsrcCtx1, "UPDATE", testDomainName + ":resource1/resource2",
                testDomainName, null);
        assertTrue(access.getGranted());

        access = zms.getAccessExt(rsrcCtx1, "UPDATE", testDomainName + ":resource1/resource3",
                testDomainName, null);
        assertFalse(access.getGranted());
        
        access = zms.getAccessExt(rsrcCtx2, "UPDATE", testDomainName + ":resource1/resource2",
                testDomainName, null);
        assertFalse(access.getGranted());

        access = zms.getAccessExt(rsrcCtx3, "UPDATE", testDomainName + ":resource1/resource2",
                testDomainName, null);
        assertTrue(access.getGranted());

        // all three have no access to CREATE action on resource1/resource2

        access = zms.getAccessExt(rsrcCtx1, "CREATE", testDomainName + ":resource1/resource2",
                testDomainName, null);
        assertFalse(access.getGranted());

        access = zms.getAccessExt(rsrcCtx2, "CREATE", testDomainName + ":resource1/resource2",
                testDomainName, null);
        assertFalse(access.getGranted());

        access = zms.getAccessExt(rsrcCtx3, "CREATE", testDomainName + ":resource1/resource2",
                testDomainName, null);
        assertFalse(access.getGranted());

        // user2 and user3 have create access to resource2(resource3)
        
        access = zms.getAccessExt(rsrcCtx1, "CREATE", testDomainName + ":resource2(resource3)",
                testDomainName, null);
        assertFalse(access.getGranted());

        access = zms.getAccessExt(rsrcCtx2, "CREATE", testDomainName + ":resource2(resource3)",
                testDomainName, null);
        assertTrue(access.getGranted());

        access = zms.getAccessExt(rsrcCtx3, "CREATE", testDomainName + ":resource2(resource3)",
                testDomainName, null);
        assertTrue(access.getGranted());

        // user2 and user3 have access to CREATE(*)/resource3/*

        access = zms.getAccessExt(rsrcCtx1, "CREATE", testDomainName + ":resource3",
                testDomainName, null);
        assertFalse(access.getGranted());

        access = zms.getAccessExt(rsrcCtx2, "CREATE", testDomainName + ":resource3/test1",
                testDomainName, null);
        assertTrue(access.getGranted());

        access = zms.getAccessExt(rsrcCtx3, "CREATE", testDomainName + ":resource3/anothertest",
                testDomainName, null);
        assertTrue(access.getGranted());

        // user2 and user3 have access to UPDATE(*)/resource3/*

        access = zms.getAccessExt(rsrcCtx1, "UPDATE", testDomainName + ":resource3",
                testDomainName, null);
        assertFalse(access.getGranted());

        access = zms.getAccessExt(rsrcCtx2, "UPDATE", testDomainName + ":resource3/(another value)",
                testDomainName, null);
        assertTrue(access.getGranted());

        access = zms.getAccessExt(rsrcCtx3, "UPDATE", testDomainName + ":resource3/a",
                testDomainName, null);
        assertTrue(access.getGranted());

        // user1 and user3 have access to READ/resource6[*]/data1

        access = zms.getAccessExt(rsrcCtx1, "read", testDomainName + ":resource4[test1]/data1",
                testDomainName, null);
        assertTrue(access.getGranted());

        access = zms.getAccessExt(rsrcCtx2, "read", testDomainName + ":resource4[test1]/data1",
                testDomainName, null);
        assertFalse(access.getGranted());

        access = zms.getAccessExt(rsrcCtx3, "read", testDomainName + ":resource4[test another]/data1",
                testDomainName, null);
        assertTrue(access.getGranted());

        // user2 and user3 have access to access/https://*.athenz.com/*

        access = zms.getAccessExt(rsrcCtx1, "access", testDomainName + ":https://web.athenz.com/data",
                testDomainName, null);
        assertFalse(access.getGranted());

        access = zms.getAccessExt(rsrcCtx2, "access", testDomainName + ":https://web.athenz.com/data",
                testDomainName, null);
        assertTrue(access.getGranted());

        access = zms.getAccessExt(rsrcCtx2, "access", testDomainName + ":https://web.athenz.org/data",
                testDomainName, null);
        assertFalse(access.getGranted());
        
        access = zms.getAccessExt(rsrcCtx3, "access", testDomainName + ":https://web-store.athenz.com/data/path",
                testDomainName, null);
        assertTrue(access.getGranted());
        
        zms.deleteTopLevelDomain(mockDomRsrcCtx, testDomainName, auditRef);
    }

    @Test
    public void testGetAccessExtCaseSensitive() {

        final String testDomainName = "AccessDomExt1";

        TopLevelDomain dom1 = createTopLevelDomainObject(testDomainName,
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        Role role1 = createRoleObject(testDomainName, "Role1", null, "user.user1",
                "user.user3");
        zms.putRole(mockDomRsrcCtx, testDomainName, "Role1", auditRef, role1);

        Role role2 = createRoleObject(testDomainName, "Role2", null, "user.user2",
                "user.user3");
        zms.putRole(mockDomRsrcCtx, testDomainName, "Role2", auditRef, role2);

        Policy policy1 = createPolicyObject(testDomainName, "Policy1", "Role1",
                "UpdatE", testDomainName + ":ResourcE1/ResourcE2", AssertionEffect.ALLOW);
        policy1.setCaseSensitive(true);
        zms.putPolicy(mockDomRsrcCtx, testDomainName, "Policy1", auditRef, policy1);

        Policy policy2 = createPolicyObject(testDomainName, "Policy2", "Role2",
                "CreatE", testDomainName + ":ResourcE2(ResourcE3)", AssertionEffect.ALLOW);
        policy2.setCaseSensitive(true);
        zms.putPolicy(mockDomRsrcCtx, testDomainName, "Policy2", auditRef, policy2);

        Policy policy3 = createPolicyObject(testDomainName, "Policy3", "Role2",
                "*", testDomainName + ":ResourcE3/*", AssertionEffect.ALLOW);
        policy3.setCaseSensitive(true);
        zms.putPolicy(mockDomRsrcCtx, testDomainName, "Policy3", auditRef, policy3);

        Policy policy4 = createPolicyObject(testDomainName, "Policy4", "Role1",
                "ReaD", testDomainName + ":ResourcE4[*]/DatA1", AssertionEffect.ALLOW);
        policy4.setCaseSensitive(true);
        zms.putPolicy(mockDomRsrcCtx, testDomainName, "Policy4", auditRef, policy4);

        Policy policy5 = createPolicyObject(testDomainName, "Policy5", "Role2",
                "AccesS", testDomainName + ":https://*.ATHENZ.com/*", AssertionEffect.ALLOW);
        policy5.setCaseSensitive(true);
        zms.putPolicy(mockDomRsrcCtx, testDomainName, "Policy5", auditRef, policy5);

        // user1 and user3 have access to UPDATE/resource1

        Authority principalAuthority = new com.yahoo.athenz.common.server.debug.DebugPrincipalAuthority();
        Principal principal1 = principalAuthority.authenticate("v=U1;d=user;n=user1;s=signature",
                "10.11.12.13", "GET", null);
        ResourceContext rsrcCtx1 = createResourceContext(principal1);
        Principal principal2 = principalAuthority.authenticate("v=U1;d=user;n=user2;s=signature",
                "10.11.12.13", "GET", null);
        ResourceContext rsrcCtx2 = createResourceContext(principal2);
        Principal principal3 = principalAuthority.authenticate("v=U1;d=user;n=user3;s=signature",
                "10.11.12.13", "GET", null);
        ResourceContext rsrcCtx3 = createResourceContext(principal3);

        // user1 and user3 have update access to resource1/resource2

        Access access = zms.getAccessExt(rsrcCtx1, "uPDATe", testDomainName + ":resouRce1/reSource2",
                testDomainName, null);
        assertTrue(access.getGranted());

        access = zms.getAccessExt(rsrcCtx1, "UPDATE", testDomainName + ":resource1/resource3",
                testDomainName, null);
        assertFalse(access.getGranted());

        access = zms.getAccessExt(rsrcCtx2, "UPDATE", testDomainName + ":resource1/resource2",
                testDomainName, null);
        assertFalse(access.getGranted());

        access = zms.getAccessExt(rsrcCtx3, "UPdATE", testDomainName + ":resoUrce1/resourCe2",
                testDomainName, null);
        assertTrue(access.getGranted());

        // all three have no access to CREATE action on resource1/resource2

        access = zms.getAccessExt(rsrcCtx1, "CReATE", testDomainName + ":resOurce1/resourcE2",
                testDomainName, null);
        assertFalse(access.getGranted());

        access = zms.getAccessExt(rsrcCtx2, "CREAtE", testDomainName + ":resOurce1/resource2",
                testDomainName, null);
        assertFalse(access.getGranted());

        access = zms.getAccessExt(rsrcCtx3, "cREATE", testDomainName + ":resource1/resoUrce2",
                testDomainName, null);
        assertFalse(access.getGranted());

        // user2 and user3 have create access to resource2(resource3)

        access = zms.getAccessExt(rsrcCtx1, "CreatE", testDomainName + ":resource2(resource3)",
                testDomainName, null);
        assertFalse(access.getGranted());

        access = zms.getAccessExt(rsrcCtx2, "CreATE", testDomainName + ":resOUrce2(resOUrce3)",
                testDomainName, null);
        assertTrue(access.getGranted());

        access = zms.getAccessExt(rsrcCtx3, "CrEATE", testDomainName + ":resourCe2(reSource3)",
                testDomainName, null);
        assertTrue(access.getGranted());

        // user2 and user3 have access to CREATE(*)/resource3/*

        access = zms.getAccessExt(rsrcCtx1, "CreatE", testDomainName + ":resource3",
                testDomainName, null);
        assertFalse(access.getGranted());

        access = zms.getAccessExt(rsrcCtx2, "CReATE", testDomainName + ":RESource3/TesT1",
                testDomainName, null);
        assertTrue(access.getGranted());

        access = zms.getAccessExt(rsrcCtx3, "CReATE", testDomainName + ":resourCE3/AnotherTest",
                testDomainName, null);
        assertTrue(access.getGranted());

        // user2 and user3 have access to UPDATE(*)/resource3/*

        access = zms.getAccessExt(rsrcCtx1, "UPDaTE", testDomainName + ":ResourcE3",
                testDomainName, null);
        assertFalse(access.getGranted());

        access = zms.getAccessExt(rsrcCtx2, "UPDATE", testDomainName + ":RESOURCE3/(anotheR Value)",
                testDomainName, null);
        assertTrue(access.getGranted());

        access = zms.getAccessExt(rsrcCtx3, "UPDaTE", testDomainName + ":resOurce3/a",
                testDomainName, null);
        assertTrue(access.getGranted());

        // user1 and user3 have access to READ/resource6[*]/data1

        access = zms.getAccessExt(rsrcCtx1, "REad", testDomainName + ":ResourCE4[TeSt1]/dAtA1",
                testDomainName, null);
        assertTrue(access.getGranted());

        access = zms.getAccessExt(rsrcCtx2, "reaD", testDomainName + ":Resource4[test1]/dAta1",
                testDomainName, null);
        assertFalse(access.getGranted());

        access = zms.getAccessExt(rsrcCtx3, "reaD", testDomainName + ":resouRce4[tesT another]/daTa1",
                testDomainName, null);
        assertTrue(access.getGranted());

        // user2 and user3 have access to access/https://*.athenz.com/*

        access = zms.getAccessExt(rsrcCtx1, "ACCess", testDomainName + ":https://Web.athenz.COM/datA",
                testDomainName, null);
        assertFalse(access.getGranted());

        access = zms.getAccessExt(rsrcCtx2, "acCess", testDomainName + ":https://web.ATHENZ.com/data",
                testDomainName, null);
        assertTrue(access.getGranted());

        access = zms.getAccessExt(rsrcCtx2, "acCess", testDomainName + ":https://web.ATHENZ.org/data",
                testDomainName, null);
        assertFalse(access.getGranted());

        access = zms.getAccessExt(rsrcCtx3, "acCess", testDomainName + ":https://web-store.ATHENZ.com/data/path",
                testDomainName, null);
        assertTrue(access.getGranted());

        zms.deleteTopLevelDomain(mockDomRsrcCtx, testDomainName, auditRef);
    }

    @Test
    public void testValidateEntity() {
        int code = 400;
        String en ="entityOne";
        Entity entity = new Entity();
        String nonmatchName ="entityTwo";
        
        // tests the condition: if (!en.equals(entity.getName()))...
        try {
            entity.setName(nonmatchName);
            
            zms.validateEntity(en, entity);
            fail("requesterror not thrown.");
        } catch (ResourceException e) {
            assertEquals(e.getCode(), code);
        }
        
        // tests the condition: if (entity.getValue() == null)...
        try {
            entity.setName(en);
            
            zms.validateEntity(en, entity);
            fail("requesterror not thrown.");
        } catch (ResourceException e) {
            assertEquals(e.getCode(), code);
        }
    }
    
    @Test
    public void testValidateDomainTemplate() {
        DomainTemplate domainTemplate = new DomainTemplate();
        List<String> names = new ArrayList<>();
        names.add("vipng");
        domainTemplate.setTemplateNames(names);
        
        List<TemplateParam> params = new ArrayList<>();
        params.add(new TemplateParam().setName("param_name_valid").setValue("param_value_valid"));
        domainTemplate.setParams(params);
        
        // our validation should be successful
        
        zms.validate(domainTemplate, "DomainTemplate", "testValidateDomainTemplate");
        
        // now let's add an invalid entry
        
        params.add(new TemplateParam().setName("param_name_invalid.test").setValue("param_value_valid"));
        try {
            zms.validate(domainTemplate, "DomainTemplate", "testValidateDomainTemplate");
            fail();
        } catch (ResourceException ignored) {
        }

        // remove the second element and add another with invalid value
        
        params.remove(1);
        params.add(new TemplateParam().setName("param_name_valid").setValue("param_value_invalid(again)"));
        try {
            zms.validate(domainTemplate, "DomainTemplate", "testValidateDomainTemplate");
            fail();
        } catch (ResourceException ignored) {
        }
    }
    
    @Test
    public void testValidateRole() {
        Role role = new Role();
        role.setName("athenz:role.role1");
        List<RoleMember> roleMembers = new ArrayList<>();
        roleMembers.add(new RoleMember().setMemberName("user.joe"));
        role.setRoleMembers(roleMembers);
        
        // first validation should be successful
        
        zms.validate(role, "Role", "testValidateRole");
        
        // now let's add invalid entry
        
        roleMembers.add(new RoleMember().setMemberName("user joe"));
        try {
            zms.validate(role, "Role", "testValidateRole");
            fail();
        } catch (ResourceException ignored) {
        }
    }
    
    @Test
    public void testPutEntity() {
        int code = 404;
        try {
            final String name = "entityOne";
            Entity entity = createEntityObject(name);
            
            // entityName will not match entity.name.
            zms.putEntity(mockDomRsrcCtx, "wrongDomainName", name, auditRef, entity);
            fail("notfounderror not thrown.");
        } catch (ResourceException e) {
            assertEquals(e.getCode(), code);
        }
    }
    
    @Test
    public void testGetPublicKeyZMS() {

        loadServerPublicKeys(zms);

        String publicKey = zms.getPublicKey("sys.auth", "zms", "0");
        assertNotNull(publicKey);
        assertEquals(pubKey, Crypto.ybase64(publicKey.getBytes(StandardCharsets.UTF_8)));

        publicKey = zms.getPublicKey("sys.auth", "zms", "1");
        assertNotNull(publicKey);
        assertEquals(pubKeyK1, Crypto.ybase64(publicKey.getBytes(StandardCharsets.UTF_8)));

        publicKey = zms.getPublicKey("sys.auth", "zms", "2");
        assertNotNull(publicKey);
        assertEquals(pubKeyK2, Crypto.ybase64(publicKey.getBytes(StandardCharsets.UTF_8)));
    }
    
    @Test
    public void testGetPublicKeyInvalidService() {

        String pubKey = zms.getPublicKey("sys.auth", "sys.auth", "0");
        assertNull(pubKey);
    }

    @Test
    public void testGetPublicKeyService() {

        TopLevelDomain dom1 = createTopLevelDomainObject("GetPublicKeyDom1",
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        ServiceIdentity service = createServiceObject("GetPublicKeyDom1",
                "Service1", "http://localhost", "/usr/bin/java", "root",
                "users", "host1");

        zms.putServiceIdentity(mockDomRsrcCtx, "GetPublicKeyDom1", "Service1", auditRef, service);

        String publicKey = zms.getPublicKey("GetPublicKeyDom1", "Service1", "0");
        assertNull(publicKey);

        assertNull(zms.getPublicKey("GetPublicKeyDom1", null, "0"));
        assertNull(zms.getPublicKey("GetPublicKeyDom1", "Service1", null));
        
        publicKey = zms.getPublicKey("GetPublicKeyDom1", "Service1", "1");
        assertNotNull(publicKey);
        assertEquals(publicKey, Crypto.ybase64DecodeString(pubKeyK1));

        publicKey = zms.getPublicKey("GetPublicKeyDom1", "Service1", "2");
        assertNotNull(publicKey);
        assertEquals(publicKey, Crypto.ybase64DecodeString(pubKeyK2));

        zms.deleteTopLevelDomain(mockDomRsrcCtx, "GetPublicKeyDom1", auditRef);
    }
    
    @Test
    public void testPutTenancy() {

        setupTenantDomainProviderService("AddTenancyDom1", "coretech", "storage",
                "http://localhost:8090/provider");

        Tenancy tenant = createTenantObject("AddTenancyDom1", "coretech.storage");
        zms.putTenancy(mockDomRsrcCtx, "AddTenancyDom1", "coretech.storage", auditRef, tenant);

        // now set up the tenant for the sub domain provider

        List<TenantRoleAction> roleActions = new ArrayList<>();
        for (Struct.Field f : TABLE_PROVIDER_ROLE_ACTIONS) {
            roleActions.add(new TenantRoleAction().setRole(f.name()).setAction(
                    (String) f.value()));
        }

        ProviderResourceGroupRoles providerRoles = new ProviderResourceGroupRoles()
                .setDomain("coretech").setService("storage")
                .setTenant("AddTenancyDom1").setRoles(roleActions)
                .setResourceGroup("set1");
        zms.putProviderResourceGroupRoles(mockDomRsrcCtx, "AddTenancyDom1", "coretech",
                "storage", "set1", auditRef, providerRoles);

        // make sure our roles have been created
        
        Role role = zms.getRole(mockDomRsrcCtx, "AddTenancyDom1", "tenancy.coretech.storage.admin", false, false, false);
        assertNotNull(role);

        role = zms.getRole(mockDomRsrcCtx, "AddTenancyDom1", "coretech.storage.res_group.set1.admin", false, false, false);
        assertNotNull(role);

        role = zms.getRole(mockDomRsrcCtx, "AddTenancyDom1", "coretech.storage.res_group.set1.reader", false, false, false);
        assertNotNull(role);
        
        role = zms.getRole(mockDomRsrcCtx, "AddTenancyDom1", "coretech.storage.res_group.set1.writer", false, false, false);
        assertNotNull(role);
        
        // verify the policies have the correct roles
        
        Policy policy = zms.getPolicy(mockDomRsrcCtx, "AddTenancyDom1", "tenancy.coretech.storage.admin");
        assertNotNull(policy);
        
        List<Assertion> assertList = policy.getAssertions();
        assertEquals(3, assertList.size());
        
        boolean domainAdminRoleCheck = false;
        boolean tenantAdminRoleCheck = false;
        boolean tenantUpdateCheck = false;
        for (Assertion obj : assertList) {
            assertEquals(AssertionEffect.ALLOW, obj.getEffect());
            switch (obj.getRole()) {
                case "addtenancydom1:role.admin":
                    assertEquals(obj.getAction(), "assume_role");
                    domainAdminRoleCheck = true;
                    break;
                case "addtenancydom1:role.tenancy.coretech.storage.admin":
                    if (obj.getAction().equals("assume_role")) {
                        tenantAdminRoleCheck = true;
                    } else if (obj.getAction().equals("update")) {
                        tenantUpdateCheck = true;
                    }
                    break;
            }
        }
        assertTrue(domainAdminRoleCheck);
        assertTrue(tenantAdminRoleCheck);
        assertTrue(tenantUpdateCheck);

        policy = zms.getPolicy(mockDomRsrcCtx, "AddTenancyDom1", "tenancy.coretech.storage.res_group.set1.reader");
        assertNotNull(policy);

        assertList = policy.getAssertions();
        assertEquals(assertList.size(), 1);
        assertEquals(assertList.get(0).getRole(), "addtenancydom1:role.coretech.storage.res_group.set1.reader");
        
        policy = zms.getPolicy(mockDomRsrcCtx, "AddTenancyDom1", "tenancy.coretech.storage.res_group.set1.writer");
        assertNotNull(policy);

        assertList = policy.getAssertions();
        assertEquals(assertList.size(), 1);
        assertEquals(assertList.get(0).getRole(), "addtenancydom1:role.coretech.storage.res_group.set1.writer");

        zms.deleteTenancy(mockDomRsrcCtx, "AddTenancyDom1", "coretech.storage", auditRef);
        
        zms.deleteTopLevelDomain(mockDomRsrcCtx, "AddTenancyDom1", auditRef);
        zms.deleteTopLevelDomain(mockDomRsrcCtx, "coretech", auditRef);
    }

    @Test
    public void testPutTenancyWithAuthorizedService() {

        String tenantDomain = "puttenancyauthorizedservice";
        String providerService  = "storage";
        String providerDomain = "coretech";
        String provider = providerDomain + "." + providerService;
        
        setupTenantDomainProviderService(tenantDomain, providerDomain, providerService, null);
        
        // tenant is setup so let's setup up policy to authorize access to tenants
        // without this role/policy we won't be authorized to add tenant roles
        // to the provider domain even with authorized service details
        
        Role role = createRoleObject(providerDomain, "self_serve", null,
                providerDomain + "." + providerService, null);
        zms.putRole(mockDomRsrcCtx, providerDomain, "self_serve", auditRef, role);
        
        Policy policy = createPolicyObject(providerDomain, "self_serve",
                "self_serve", "update", providerDomain + ":tenant.*", AssertionEffect.ALLOW);
        zms.putPolicy(mockDomRsrcCtx, providerDomain, "self_serve", auditRef, policy);
        
        // we are going to create a principal object with authorized service
        // set to coretech.storage
        
        Authority principalAuthority = new com.yahoo.athenz.common.server.debug.DebugPrincipalAuthority();
        String userId = "user1";
        String unsignedCreds = "v=U1;d=user;u=" + userId;
        Principal principal = SimplePrincipal.create("user", userId, unsignedCreds + ";s=signature", 0, principalAuthority);
        assertNotNull(principal);
        ((SimplePrincipal) principal).setUnsignedCreds(unsignedCreds);
        ((SimplePrincipal) principal).setAuthorizedService(provider);
        ResourceContext ctx = createResourceContext(principal, "puttenancy");
        
        // after this call we should have admin roles set for both provider and tenant
        
        Tenancy tenant = createTenantObject(tenantDomain, provider);
        zms.putTenancy(ctx, tenantDomain, provider, auditRef, tenant);

        // make sure our policy has been created
        
        policy = zms.getPolicy(mockDomRsrcCtx, tenantDomain, "tenancy." + provider + ".admin");
        assertNotNull(policy);
        
        String tenantRoleInProviderDomain = providerService + ".tenant." + tenantDomain + ".admin";
        
        List<Assertion> assertList = policy.getAssertions();
        assertEquals(3, assertList.size());
        boolean domainAdminRoleCheck = false;
        boolean tenantAdminRoleCheck = false;
        boolean tenantUpdateCheck = false;
        for (Assertion obj : assertList) {
            assertEquals(AssertionEffect.ALLOW, obj.getEffect());
            if (obj.getRole().equals(tenantDomain + ":role.admin")) {
                assertEquals("assume_role", obj.getAction());
                assertEquals("coretech:role.storage.tenant.puttenancyauthorizedservice.admin", obj.getResource());
                domainAdminRoleCheck = true;
            } else if (obj.getRole().equals(tenantDomain + ":role.tenancy." + provider + ".admin")) {
                if (obj.getAction().equals("assume_role")) {
                    assertEquals("coretech:role.storage.tenant.puttenancyauthorizedservice.admin", obj.getResource());
                    tenantAdminRoleCheck = true;
                } else if (obj.getAction().equals("update")) {
                    assertEquals(tenantDomain + ":tenancy." + provider, obj.getResource());
                    tenantUpdateCheck = true;
                }
            }
        }
        assertTrue(domainAdminRoleCheck);
        assertTrue(tenantAdminRoleCheck);
        assertTrue(tenantUpdateCheck);
            
        // now let's call delete tenancy support with the same authorized service token
        
        zms.deleteTenancy(ctx, tenantDomain,  provider, auditRef);

        // verify that all roles and policies have been deleted
        
        try {
            zms.getPolicy(mockDomRsrcCtx, tenantDomain, "tenancy." + provider + ".admin");
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 404);
        }
        
        try {
            zms.getRole(mockDomRsrcCtx, providerDomain, tenantRoleInProviderDomain, false, false, false);
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 404);
        }
        
        // clean up our domains
        
        zms.deleteTopLevelDomain(mockDomRsrcCtx, tenantDomain, auditRef);
        zms.deleteTopLevelDomain(mockDomRsrcCtx, providerDomain, auditRef);
    }
    
    @Test
    public void testPutTenancyWithAuthorizedServiceMismatch() {

        TestAuditLogger alogger = new TestAuditLogger();
        ZMSImpl zmsImpl = getZmsImpl(alogger);

        String tenantDomain = "puttenancyauthorizedservicemismatch";
        String providerService  = "storage";
        String providerDomain = "coretech-test";
        String provider = providerDomain + "." + providerService;
        
        setupTenantDomainProviderService(zmsImpl, tenantDomain, providerDomain, providerService, null);
        
        // tenant is setup so let's setup up policy to authorize access to tenants
        // without this role/policy we won't be authorized to add tenant roles
        // to the provider domain even with authorized service details
        
        Role role = createRoleObject(providerDomain, "self_serve", null,
                providerDomain + "." + providerService, null);
        zmsImpl.putRole(mockDomRsrcCtx, providerDomain, "self_serve", auditRef, role);
        
        Policy policy = createPolicyObject(providerDomain, "self_serve",
                "self_serve", "update", providerDomain + ":tenant.*", AssertionEffect.ALLOW);
        zmsImpl.putPolicy(mockDomRsrcCtx, providerDomain, "self_serve", auditRef, policy);
        
        // we are going to create a principal object with authorized service
        // set to coretech.storage
        
        Authority principalAuthority = new com.yahoo.athenz.common.server.debug.DebugPrincipalAuthority();
        String userId = "user1";
        String unsignedCreds = "v=U1;d=user;u=" + userId;
        Principal principal = SimplePrincipal.create("user", userId, unsignedCreds + ";s=signature", 0, principalAuthority);
        assertNotNull(principal);
        ((SimplePrincipal) principal).setUnsignedCreds(unsignedCreds);
        ((SimplePrincipal) principal).setAuthorizedService("coretech.storage"); // make provider mismatch
        ResourceContext ctx = createResourceContext(principal, "puttenancy");
        
        // this should fail since the authorized service name does not
        // match to the provider
        
        Tenancy tenant = createTenantObject(tenantDomain, provider);
        try {
            zmsImpl.putTenancy(ctx, tenantDomain, provider, auditRef, tenant);
            fail();
        } catch (ResourceException ex) {
            assertEquals(400, ex.getCode());
        }
        
        // clean up our domains
        
        zmsImpl.deleteTopLevelDomain(mockDomRsrcCtx, tenantDomain, auditRef);
        zmsImpl.deleteTopLevelDomain(mockDomRsrcCtx, providerDomain, auditRef);
    }
    
    @Test
    public void testPutTenancyWithoutTenantRoles() {

        setupTenantDomainProviderService("AddTenancyDom1", "coretech", "storage",
                "http://localhost:8090/provider");

        Tenancy tenant = createTenantObject("AddTenancyDom1", "coretech.storage");
        zms.putTenancy(mockDomRsrcCtx, "AddTenancyDom1", "coretech.storage", auditRef, tenant);
        
        // make sure our roles have not been created
        
        try {
            zms.getRole(mockDomRsrcCtx, "AddTenancyDom1", "coretech.storage.admin", false, false, false);
            fail();
        } catch (ResourceException ex) {
            assertEquals(404, ex.getCode());
        }
        
        try {
            zms.getRole(mockDomRsrcCtx, "AddTenancyDom1", "coretech.storage.reader", false, false, false);
            fail();
        } catch (ResourceException ex) {
            assertEquals(404, ex.getCode());
        }
        
        try {
            zms.getRole(mockDomRsrcCtx, "AddTenancyDom1", "coretech.storage.writer", false, false, false);
            fail();
        } catch (ResourceException ex) {
            assertEquals(404, ex.getCode());
        }
        
        // verify the admin policy has been successfully created
        
        Policy policy = zms.getPolicy(mockDomRsrcCtx, "AddTenancyDom1", "tenancy.coretech.storage.admin");
        assertNotNull(policy);

        // we should not have other policies for actions
        
        try {
            zms.getPolicy(mockDomRsrcCtx, "AddTenancyDom1", "tenancy.coretech.storage.reader");
            fail();
        } catch (ResourceException ex) {
            assertEquals(404, ex.getCode());
        }
        
        try {
            zms.getPolicy(mockDomRsrcCtx, "AddTenancyDom1", "tenancy.coretech.storage.writer");
            fail();
        } catch (ResourceException ex) {
            assertEquals(404, ex.getCode());
        }
        
        zms.deleteTenancy(mockDomRsrcCtx, "AddTenancyDom1", "coretech.storage", auditRef);
        
        zms.deleteTopLevelDomain(mockDomRsrcCtx, "AddTenancyDom1", auditRef);
        zms.deleteTopLevelDomain(mockDomRsrcCtx, "coretech", auditRef);
    }

    @Test
    public void testPutTenancyInvalidService() {

        TopLevelDomain dom1 = createTopLevelDomainObject("providerdomaintenancy",
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        Tenancy tenant = new Tenancy().setDomain("sports").setService("providerdomaintenancy.api");
        try {
            zms.putTenancy(mockDomRsrcCtx, "sports", "providerdomaintenancy.api", auditRef, tenant);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 404);
        }

        zms.deleteTopLevelDomain(mockDomRsrcCtx, "providerdomaintenancy", auditRef);
    }

    @Test
    public void testPutTenancyMissingAuditRef() {
        String tenantDomain    = "testPutTenancyMissingAuditRef";
        String providerDomain  = "providerTestPutTenancyMissingAuditRef";
        String providerService = "storage";

        // create tenant and provider domains

        setupTenantDomainProviderService(tenantDomain, providerDomain, providerService,
                "http://localhost:8090/provider");

        // modify the tenant domain to require auditing

        DomainMeta meta = createDomainMetaObject("Tenant Domain", null, true, true, null, 0);
        zms.putDomainMeta(mockDomRsrcCtx, tenantDomain, auditRef, meta);
        zms.putDomainSystemMeta(mockDomRsrcCtx, tenantDomain, "auditenabled", auditRef, meta);

        Tenancy tenant = createTenantObject(tenantDomain, providerDomain + "." + providerService);
        try {
            zms.putTenancy(mockDomRsrcCtx, tenantDomain, providerDomain + "." + providerService, null, tenant);
            fail("requesterror not thrown by putTenancy.");
        } catch (ResourceException ex) {
            assertEquals(400, ex.getCode());
            assertTrue(ex.getMessage().contains("Audit reference required"));
        } finally {
            zms.deleteTopLevelDomain(mockDomRsrcCtx, tenantDomain, auditRef);
            zms.deleteTopLevelDomain(mockDomRsrcCtx, providerDomain, auditRef);
        }
    }

    @Test
    public void testPutTenancyMismatchObject() {
        String tenantDomain    = "testPutTenancyMismatchObject";
        String providerDomain  = "providerTestPutTenancyMismatchObject";
        String providerService = "storage";

        // create tenant and provider domains

        setupTenantDomainProviderService(tenantDomain, providerDomain, providerService,
                "http://localhost:8090/provider");

        Tenancy tenant = createTenantObject(tenantDomain + "test", providerDomain + "." + providerService);
        try {
            zms.putTenancy(mockDomRsrcCtx, tenantDomain, providerDomain + "." + providerService, auditRef, tenant);
            fail("request error not thrown by putTenancy.");
        } catch (ResourceException ex) {
            assertEquals(400, ex.getCode());
        } finally {
            zms.deleteTopLevelDomain(mockDomRsrcCtx, tenantDomain, auditRef);
            zms.deleteTopLevelDomain(mockDomRsrcCtx, providerDomain, auditRef);
        }
    }

    @Test
    public void testDeleteTenancy() {
        String tenantDomain    = "testDeleteTenancy";
        String providerDomain  = "providerTestDeleteTenancy";
        String providerService = "storage";
        String provService = providerDomain + "." + providerService;
        
        // create tenant and provider domains
        //
        setupTenantDomainProviderService(tenantDomain, providerDomain, providerService,
                "http://localhost:8090/provider");

        // modify the tenant domain to require auditing
        //
        DomainMeta meta =
            createDomainMetaObject("Tenant Domain", null, true, true, null, 0);
        zms.putDomainMeta(mockDomRsrcCtx, tenantDomain, auditRef, meta);

        String testRoleName = providerDomain + ".testrole";
        Role role = createRoleObject(tenantDomain, testRoleName, null, "user.joe", "user.jane");
        zms.putRole(mockDomRsrcCtx, tenantDomain, testRoleName, auditRef, role);

        // setup tenancy
        //
        Tenancy tenant = createTenantObject(tenantDomain, provService);
        zms.putTenancy(mockDomRsrcCtx, tenantDomain, provService, auditRef, tenant);

        try {
            zms.deleteTenancy(mockDomRsrcCtx, tenantDomain,  provService, auditRef);
            
            // verify we didn't delete a role by mistake
            
            assertNotNull(zms.getRole(mockDomRsrcCtx, tenantDomain, testRoleName, false, false, false));
            
            // verify that all roles and policies have been deleted
            
            try {
                zms.getRole(mockDomRsrcCtx, tenantDomain, provService + ".admin", false, false, false);
                fail();
            } catch (ResourceException ex) {
                assertEquals(ex.getCode(), 404);
            }
            
            try {
                zms.getRole(mockDomRsrcCtx, tenantDomain, provService + ".reader", false, false, false);
                fail();
            } catch (ResourceException ex) {
                assertEquals(ex.getCode(), 404);
            }
            try {
                zms.getRole(mockDomRsrcCtx, tenantDomain, provService + ".writer", false, false, false);
                fail();
            } catch (ResourceException ex) {
                assertEquals(ex.getCode(), 404);
            }

            try {
                zms.getPolicy(mockDomRsrcCtx, tenantDomain, "tenancy." + provService + ".admin");
                fail();
            } catch (ResourceException ex) {
                assertEquals(ex.getCode(), 404);
            }
            
            try {
                zms.getPolicy(mockDomRsrcCtx, tenantDomain, "tenancy." + provService + ".reader");
                fail();
            } catch (ResourceException ex) {
                assertEquals(ex.getCode(), 404);
            }
            
            try {
                zms.getPolicy(mockDomRsrcCtx, tenantDomain, "tenancy." + provService + ".writer");
                fail();
            } catch (ResourceException ex) {
                assertEquals(ex.getCode(), 404);
            }
            
        } finally {
            zms.deleteTopLevelDomain(mockDomRsrcCtx, tenantDomain, auditRef);
            zms.deleteTopLevelDomain(mockDomRsrcCtx, providerDomain, auditRef);
        }
    }

    @Test
    public void testDeleteTenancyMissingService() {
        String tenantDomain    = "testDeleteTenancy";
        String providerDomain  = "providerTestDeleteTenancy";
        String providerService = "storage";
        String provService = providerDomain + "." + providerService;

        // create tenant and provider domains
        //
        setupTenantDomainProviderService(tenantDomain, providerDomain, providerService,
                "http://localhost:8090/provider");

        // modify the tenant domain to require auditing
        //
        DomainMeta meta =
            createDomainMetaObject("Tenant Domain", null, true, true, null, 0);
        zms.putDomainMeta(mockDomRsrcCtx, tenantDomain, auditRef, meta);

        // setup tenancy
        //
        Tenancy tenant = createTenantObject(tenantDomain, provService);
        zms.putTenancy(mockDomRsrcCtx, tenantDomain, provService, auditRef, tenant);

        // delete the provider service
        
        zms.deleteServiceIdentity(mockDomRsrcCtx, providerDomain, providerService, auditRef);
        
        try {
            zms.deleteTenancy(mockDomRsrcCtx, tenantDomain, provService, auditRef);
            fail();
        } catch (ResourceException ex) {
            assertEquals(404, ex.getCode());
            assertTrue(ex.getMessage().contains("Unable to retrieve service"));
        } finally {
            zms.deleteTopLevelDomain(mockDomRsrcCtx, tenantDomain, auditRef);
            zms.deleteTopLevelDomain(mockDomRsrcCtx, providerDomain, auditRef);
        }
    }
    
    @Test
    public void testDeleteTenancyMissingAuditRef() {
        String tenantDomain    = "testDeleteTenancyMissingAuditRef";
        String providerDomain  = "providerTestDeleteTenancyMissingAuditRef";
        String providerService = "storage";

        // create tenant and provider domains
        //
        setupTenantDomainProviderService(tenantDomain, providerDomain, providerService,
                "http://localhost:8090/provider");

        // modify the tenant domain to require auditing
        //
        DomainMeta meta =
            createDomainMetaObject("Tenant Domain", null, true, true, null, 0);
        zms.putDomainMeta(mockDomRsrcCtx, tenantDomain, auditRef, meta);
        zms.putDomainSystemMeta(mockDomRsrcCtx, tenantDomain, "auditenabled", auditRef, meta);
        zms.putDomainSystemMeta(mockDomRsrcCtx, tenantDomain, "enabled", auditRef, meta);

        // setup tenancy
        //
        Tenancy tenant = createTenantObject(tenantDomain, providerDomain + "." + providerService);
        zms.putTenancy(mockDomRsrcCtx, tenantDomain, providerDomain + "." + providerService, auditRef, tenant);

        try {
            zms.deleteTenancy(mockDomRsrcCtx, tenantDomain,  providerDomain + "." + providerService, null);
            fail("requesterror not thrown by deleteTenancy.");
        } catch (ResourceException ex) {
            assertEquals(400, ex.getCode());
            assertTrue(ex.getMessage().contains("Audit reference required"));
        } finally {
            zms.deleteTopLevelDomain(mockDomRsrcCtx, tenantDomain, auditRef);
            zms.deleteTopLevelDomain(mockDomRsrcCtx, providerDomain, auditRef);
        }
    }
    
    @Test
    public void testPutTenantRolesWithResourceGroup() {

        String domain = "testPutTenantRoles";
        TopLevelDomain dom = createTopLevelDomainObject(
                domain, "Test Domain1", "testOrg", adminUser);
        dom.setAuditEnabled(true);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom);

        List<TenantRoleAction> roleActions = new ArrayList<>();
        for (Struct.Field f : TABLE_PROVIDER_ROLE_ACTIONS) {
            roleActions.add(new TenantRoleAction().setRole(f.name()).setAction(
                    (String) f.value()));
        }
        String serviceName  = "storage";
        String tenantDomain = "tenantTestPutTenantRoles";
        String resourceGroup = "Group1";
        
        TenantResourceGroupRoles tenantRoles = new TenantResourceGroupRoles().setDomain(domain)
                .setService(serviceName).setTenant(tenantDomain)
                .setRoles(roleActions).setResourceGroup(resourceGroup);
        zms.putTenantResourceGroupRoles(mockDomRsrcCtx, domain, serviceName, tenantDomain, resourceGroup,
                auditRef, tenantRoles);

        TenantResourceGroupRoles tRoles = zms.getTenantResourceGroupRoles(mockDomRsrcCtx, domain, serviceName,
                tenantDomain, resourceGroup);
        assertNotNull(tRoles);
        assertEquals(domain.toLowerCase(), tRoles.getDomain());
        assertEquals(serviceName.toLowerCase(), tRoles.getService());
        assertEquals(tenantDomain.toLowerCase(), tRoles.getTenant());
        assertEquals(resourceGroup.toLowerCase(), tRoles.getResourceGroup());
        assertEquals(TABLE_PROVIDER_ROLE_ACTIONS.size(), tRoles.getRoles().size());

        zms.deleteTopLevelDomain(mockDomRsrcCtx, domain, auditRef);
    }

    @Test
    public void testPutTenantRolesWithResourceGroupEmptyRoleActions() {

        String domain = "testputtenantrolesnoroles";
        TopLevelDomain dom = createTopLevelDomainObject(
                domain, "Test Domain1", "testOrg", adminUser);
        dom.setAuditEnabled(true);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom);

        List<TenantRoleAction> roleActions = new ArrayList<>();
        String serviceName  = "storage";
        String tenantDomain = "tenantTestPutTenantRoles";
        String resourceGroup = "Group1";

        TenantResourceGroupRoles tenantRoles = new TenantResourceGroupRoles().setDomain(domain)
                .setService(serviceName).setTenant(tenantDomain)
                .setRoles(roleActions).setResourceGroup(resourceGroup);

        try {
            zms.putTenantResourceGroupRoles(mockDomRsrcCtx, domain, serviceName, tenantDomain,
                    resourceGroup, auditRef, tenantRoles);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 400);
        }

        zms.deleteTopLevelDomain(mockDomRsrcCtx, domain, auditRef);
    }

    @Test
    public void testGetDomainDataCheck() {

        String tenantDomainName = "testGetDomainDataCheck";
        TopLevelDomain tenDom = createTopLevelDomainObject(tenantDomainName,
                "Test Provider Domain", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, tenDom);
        // create roles
        Role role1 = createRoleObject(tenantDomainName, "Role1", null, "user.joe", "user.jane");
        zms.putRole(mockDomRsrcCtx, tenantDomainName, "Role1", auditRef, role1);

        Role role2 = createRoleObject(tenantDomainName, "Role2", null, "user.phil", "user.gil");
        zms.putRole(mockDomRsrcCtx, tenantDomainName, "Role2", auditRef, role2);

        // create policies
        Policy policy1 = createPolicyObject(tenantDomainName, "Policy1", "Role1",
                "UPDATE", tenantDomainName + ":resource1", AssertionEffect.ALLOW);
        zms.putPolicy(mockDomRsrcCtx, tenantDomainName, "Policy1", auditRef, policy1);
        Policy policy2 = createPolicyObject(tenantDomainName, "Policy2", "Role2",
                "READ", tenantDomainName + ":resource1", AssertionEffect.ALLOW);
        zms.putPolicy(mockDomRsrcCtx, tenantDomainName, "Policy2", auditRef, policy2);
        //
        // test valid setup domain
        DomainDataCheck ddc = zms.getDomainDataCheck(mockDomRsrcCtx, tenantDomainName);
        assertNotNull(ddc);
        assertEquals(3, ddc.getPolicyCount());
        assertEquals(3, ddc.getAssertionCount());
        assertEquals(0, ddc.getRoleWildCardCount());
        assertNull(ddc.getDanglingRoles());
        assertNull(ddc.getDanglingPolicies());
        assertNull(ddc.getProvidersWithoutTrust());
        assertNull(ddc.getTenantsWithoutAssumeRole());

        // set valid wildcard role
        Assertion assertion = new Assertion();
        assertion.setAction("MANAGE");
        assertion.setEffect(AssertionEffect.ALLOW);
        assertion.setResource(tenantDomainName + ":wildlife");
        assertion.setRole(tenantDomainName + ":role.Role*");
        
        Policy policy = zms.getPolicy(mockDomRsrcCtx, tenantDomainName, "Policy2");
        List<Assertion> assertList = policy.getAssertions();
        assertList.add(assertion);
        policy.setAssertions(assertList);
        zms.putPolicy(mockDomRsrcCtx, tenantDomainName, "Policy2", auditRef, policy);

        ddc = zms.getDomainDataCheck(mockDomRsrcCtx, tenantDomainName);
        assertNotNull(ddc);
        assertEquals(3, ddc.getPolicyCount());
        assertEquals(4, ddc.getAssertionCount());
        assertEquals(1, ddc.getRoleWildCardCount());
        assertNull(ddc.getDanglingRoles());
        assertNull(ddc.getDanglingPolicies());
        assertNull(ddc.getProvidersWithoutTrust());
        assertNull(ddc.getTenantsWithoutAssumeRole());

        // test dangling policy with wildcard role
        assertion = new Assertion();
        assertion.setAction("MANAGE");
        assertion.setEffect(AssertionEffect.ALLOW);
        assertion.setResource(tenantDomainName + ":wildlife");
        assertion.setRole(tenantDomainName + ":role.Wild*");
        
        policy = zms.getPolicy(mockDomRsrcCtx, tenantDomainName, "Policy2");
        assertList = policy.getAssertions();
        assertList.add(assertion);
        policy.setAssertions(assertList);
        zms.putPolicy(mockDomRsrcCtx, tenantDomainName, "Policy2", auditRef, policy);

        ddc = zms.getDomainDataCheck(mockDomRsrcCtx, tenantDomainName);
        assertNotNull(ddc);
        assertEquals(3, ddc.getPolicyCount());
        assertEquals(5, ddc.getAssertionCount());
        assertEquals(2, ddc.getRoleWildCardCount());
        assertNull(ddc.getDanglingRoles());
        assertEquals(1, ddc.getDanglingPolicies().size());
        assertNull(ddc.getProvidersWithoutTrust());
        assertNull(ddc.getTenantsWithoutAssumeRole());

        // add a dangling role 
        Role role3 = createRoleObject(tenantDomainName, "Role3", null, "user.user1", "user.user3");
        zms.putRole(mockDomRsrcCtx, tenantDomainName, "Role3", auditRef, role3);

        ddc = zms.getDomainDataCheck(mockDomRsrcCtx, tenantDomainName);
        assertNotNull(ddc);
        assertEquals(3, ddc.getPolicyCount());
        assertEquals(5, ddc.getAssertionCount());
        assertEquals(2, ddc.getRoleWildCardCount());
        assertEquals(1, ddc.getDanglingRoles().size());
        assertEquals(1, ddc.getDanglingPolicies().size());
        assertNull(ddc.getProvidersWithoutTrust());
        assertNull(ddc.getTenantsWithoutAssumeRole());

        // test more dangling policies
        // create policy with assertion using unknown role
        assertion = new Assertion();
        assertion.setAction("snorkel");
        assertion.setEffect(AssertionEffect.ALLOW);
        assertion.setResource(tenantDomainName + ":molokoni");
        assertion.setRole(tenantDomainName + ":role.snorkeler");
        
        policy = zms.getPolicy(mockDomRsrcCtx, tenantDomainName, "Policy2");
        assertList = policy.getAssertions();
        assertList.add(assertion);
        policy.setAssertions(assertList);
        zms.putPolicy(mockDomRsrcCtx, tenantDomainName, "Policy2", auditRef, policy);

        ddc = zms.getDomainDataCheck(mockDomRsrcCtx, tenantDomainName);
        assertNotNull(ddc);
        assertEquals(3, ddc.getPolicyCount());
        assertEquals(6, ddc.getAssertionCount());
        assertEquals(2, ddc.getRoleWildCardCount());
        assertEquals(1, ddc.getDanglingRoles().size());
        assertEquals(2, ddc.getDanglingPolicies().size());
        assertNull(ddc.getProvidersWithoutTrust());
        assertNull(ddc.getTenantsWithoutAssumeRole());

        // create provider domain
        String provDomainTop = "testGetDomainDataCheckProvider";
        TopLevelDomain provDom = createTopLevelDomainObject(provDomainTop,
                "Test Provider Domain", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, provDom);

        String provDomainSub = provDomainTop + ".sub";
        SubDomain subDom = createSubDomainObject("sub", provDomainTop, null, null, adminUser);
        subDom.setAuditEnabled(true);
        zms.postSubDomain(mockDomRsrcCtx, provDomainTop, auditRef, subDom);

        // test incomplete tenancy setup
        // put tenancy for provider
        String provEndPoint = "http://localhost:8090/provider";
        String provSvc      = "storage";
        ServiceIdentity service = createServiceObject(
                provDomainSub, provSvc, provEndPoint,
                "/usr/bin/java", "root", "users", "localhost");

        zms.putServiceIdentity(mockDomRsrcCtx, provDomainSub, provSvc, auditRef, service);

        Tenancy tenant = createTenantObject(tenantDomainName, provDomainSub + "." + provSvc);
        zms.putTenancy(mockDomRsrcCtx, tenantDomainName, provDomainSub + "." + provSvc, auditRef, tenant);

        ddc = zms.getDomainDataCheck(mockDomRsrcCtx, tenantDomainName);
        assertNotNull(ddc);
        assertEquals(4, ddc.getPolicyCount());
        assertEquals(9, ddc.getAssertionCount());
        assertEquals(2, ddc.getRoleWildCardCount());
        assertEquals(1, ddc.getDanglingRoles().size());
        assertEquals(2, ddc.getDanglingPolicies().size());
        assertTrue(ddc.getDanglingRoles().contains("role3"));
        boolean danglingPolicy1Found = false;
        boolean danglingPolicy2Found = false;
        for (DanglingPolicy danglingPolicy : ddc.getDanglingPolicies()) {
            if (danglingPolicy.getPolicyName().equals("policy2") && danglingPolicy.getRoleName().equals("wild*")) {
                danglingPolicy1Found = true;
            } else if (danglingPolicy.getPolicyName().equals("policy2") && danglingPolicy.getRoleName().equals("snorkeler")) {
                danglingPolicy2Found = true;
            }
        }
        assertTrue(danglingPolicy1Found);
        assertTrue(danglingPolicy2Found);
        assertEquals(1, ddc.getProvidersWithoutTrust().size());
        assertNull(ddc.getTenantsWithoutAssumeRole());

        // test that now all is hunky dory between the tenant and provider
        // provider gets the trust role(s)
        List<TenantRoleAction> roleActions = new ArrayList<>();
        for (Struct.Field f : TABLE_PROVIDER_ROLE_ACTIONS) {
            roleActions.add(new TenantRoleAction().setRole(f.name()).setAction((String) f.value()));
        }

        TenantResourceGroupRoles tenantRoles = new TenantResourceGroupRoles().setDomain(provDomainSub)
                .setService(provSvc).setTenant(tenantDomainName)
                .setRoles(roleActions).setResourceGroup("set1");

        zms.putTenantResourceGroupRoles(mockDomRsrcCtx, provDomainSub, provSvc, tenantDomainName,
                "set1", auditRef, tenantRoles);

        ddc = zms.getDomainDataCheck(mockDomRsrcCtx, tenantDomainName);
        assertNotNull(ddc);
        assertEquals(4, ddc.getPolicyCount());
        assertEquals(9, ddc.getAssertionCount());
        assertEquals(2, ddc.getRoleWildCardCount());
        assertEquals(1, ddc.getDanglingRoles().size());
        assertEquals(2, ddc.getDanglingPolicies().size());
        assertNull(ddc.getTenantsWithoutAssumeRole());

        ddc = zms.getDomainDataCheck(mockDomRsrcCtx, provDomainSub);
        assertNotNull(ddc);
        assertEquals(5, ddc.getPolicyCount());
        assertEquals(5, ddc.getAssertionCount());
        assertEquals(0, ddc.getRoleWildCardCount());
        assertNull(ddc.getDanglingRoles());
        assertNull(ddc.getDanglingPolicies());
        assertNull(ddc.getProvidersWithoutTrust());
        assertNotNull(ddc.getTenantsWithoutAssumeRole());

        // test provider should report tenant is missing
        // remove the assume_role policies from the tenant
        zms.deleteTenancy(mockDomRsrcCtx, tenantDomainName,  provDomainSub + "." + provSvc, auditRef);

        ddc = zms.getDomainDataCheck(mockDomRsrcCtx, provDomainSub);
        assertNotNull(ddc);
        assertEquals(5, ddc.getPolicyCount());
        assertEquals(5, ddc.getAssertionCount());
        assertEquals(0, ddc.getRoleWildCardCount());
        assertNull(ddc.getDanglingRoles());
        assertNull(ddc.getDanglingPolicies());
        assertNull(ddc.getProvidersWithoutTrust());
         assertEquals(1, ddc.getTenantsWithoutAssumeRole().size());

        ddc = zms.getDomainDataCheck(mockDomRsrcCtx, tenantDomainName);
        assertNotNull(ddc);
        assertEquals(3, ddc.getPolicyCount());
        assertEquals(6, ddc.getAssertionCount());
        assertEquals(2, ddc.getRoleWildCardCount());
        assertEquals(2, ddc.getDanglingRoles().size());
        assertEquals(2, ddc.getDanglingPolicies().size());
        assertNull(ddc.getProvidersWithoutTrust());
        assertNull(ddc.getTenantsWithoutAssumeRole());

        // test service name with resource group
        // setup up the top level domain+service with resource group
        String provSvcTop = "shelter";
        service = createServiceObject(
                provDomainTop, provSvcTop, provEndPoint,
                "/usr/bin/java", "root", "users", "localhost");

        zms.putServiceIdentity(mockDomRsrcCtx, provDomainTop, provSvcTop, auditRef, service);

        TenantResourceGroupRoles tenantGroupRoles = new TenantResourceGroupRoles()
                .setDomain(provDomainTop)
                .setService(provSvcTop).setTenant(tenantDomainName)
                .setRoles(roleActions).setResourceGroup("ravers");
        // put the trust roles with resource group into top level provider domain
        // - tenant is not yet supporting the top level domain
        zms.putTenantResourceGroupRoles(mockDomRsrcCtx, provDomainTop, provSvcTop, tenantDomainName, "ravers",
                auditRef, tenantGroupRoles);

        ddc = zms.getDomainDataCheck(mockDomRsrcCtx, provDomainTop);
        assertNotNull(ddc);
        assertEquals(5, ddc.getPolicyCount());
        assertEquals(5, ddc.getAssertionCount());
        assertEquals(0, ddc.getRoleWildCardCount());
        assertNull(ddc.getDanglingRoles());
        assertNull(ddc.getDanglingPolicies());
        assertNull(ddc.getProvidersWithoutTrust());
        assertEquals(1, ddc.getTenantsWithoutAssumeRole().size());

        // now set up the tenant for the sub domain provider
        ProviderResourceGroupRoles providerRoles = new ProviderResourceGroupRoles()
                .setDomain(provDomainSub).setService(provSvc)
                .setTenant(tenantDomainName).setRoles(roleActions)
                .setResourceGroup("ravers");
        // this sets up the assume roles in the tenant for the sub domain
        // if it is an authorized service, then it will setup the provider roles too
        zms.putProviderResourceGroupRoles(mockDomRsrcCtx, tenantDomainName, provDomainSub, provSvc,
                "ravers", auditRef, providerRoles);

        // tenant sees that the subdomain provider isn't provisioned yet
        // for the resource group: testgetdomaindatacheckprovider.sub.storage.ravers
        ddc = zms.getDomainDataCheck(mockDomRsrcCtx, tenantDomainName);
        assertNotNull(ddc, ddc.toString());
        assertEquals(7, ddc.getPolicyCount());
        assertEquals(12, ddc.getAssertionCount());
        assertEquals(2, ddc.getRoleWildCardCount());
        assertEquals(1, ddc.getDanglingRoles().size());
        assertEquals(2, ddc.getDanglingPolicies().size());
        assertEquals(1, ddc.getProvidersWithoutTrust().size());
        assertNull(ddc.getTenantsWithoutAssumeRole());

        // setup tenancy in the tenant domain for the provider subdomain
        zms.putTenancy(mockDomRsrcCtx, tenantDomainName, provDomainSub + "." + provSvc, auditRef, tenant);

        // the subdomain provider believes it is in sync with tenant
        ddc = zms.getDomainDataCheck(mockDomRsrcCtx, provDomainSub);
        assertNotNull(ddc);
        assertEquals(5, ddc.getPolicyCount());
        assertEquals(5, ddc.getAssertionCount());
        assertEquals(0, ddc.getRoleWildCardCount());
        assertNull(ddc.getDanglingRoles());
        assertNull(ddc.getDanglingPolicies());
        assertNull(ddc.getProvidersWithoutTrust());
        assertNotNull(ddc.getTenantsWithoutAssumeRole());

        // but the tenant sees the sub provider is not setup 
        ddc = zms.getDomainDataCheck(mockDomRsrcCtx, tenantDomainName);
        assertNotNull(ddc);
        assertEquals(7, ddc.getPolicyCount());
        assertEquals(12, ddc.getAssertionCount());
        assertEquals(2, ddc.getRoleWildCardCount());
        assertEquals(1, ddc.getDanglingRoles().size());
        assertEquals(2, ddc.getDanglingPolicies().size());
        assertEquals(1, ddc.getProvidersWithoutTrust().size());
        assertNull(ddc.getTenantsWithoutAssumeRole());

        // now set up the sub domain provider for the tenant with resource groups
        // so tenant and the sub domain provider are in sync again
        // add resource groups to provider
        tenantGroupRoles = new TenantResourceGroupRoles()
                .setDomain(provDomainSub)
                .setService(provSvc).setTenant(tenantDomainName)
                .setRoles(roleActions).setResourceGroup("ravers");
        // put the trust roles into sub domain provider
        zms.putTenantResourceGroupRoles(mockDomRsrcCtx, provDomainSub, provSvc, tenantDomainName, "ravers",
                auditRef, tenantGroupRoles);

        // now tenant sees the sub domain has provisioned it
        ddc = zms.getDomainDataCheck(mockDomRsrcCtx, tenantDomainName);
        assertNotNull(ddc);
        assertEquals(7, ddc.getPolicyCount());
        assertEquals(12, ddc.getAssertionCount());
        assertEquals(2, ddc.getRoleWildCardCount());
        assertEquals(1, ddc.getDanglingRoles().size());
        assertEquals(2, ddc.getDanglingPolicies().size());
        assertNull(ddc.getProvidersWithoutTrust());
        assertNull(ddc.getTenantsWithoutAssumeRole());

        // now set up the tenant for the top level domain provider
        // so tenant and the top level domain provider are in sync again
        providerRoles = new ProviderResourceGroupRoles()
                .setDomain(provDomainTop).setService(provSvcTop)
                .setTenant(tenantDomainName).setRoles(roleActions)
                .setResourceGroup("ravers");
        // this sets up the assume roles in the tenant for the top level domain
        zms.putProviderResourceGroupRoles(mockDomRsrcCtx, tenantDomainName, provDomainTop, provSvcTop,
                "ravers", auditRef, providerRoles);

        ddc = zms.getDomainDataCheck(mockDomRsrcCtx, tenantDomainName);
        assertNotNull(ddc);
        assertEquals(11, ddc.getPolicyCount());
        assertEquals(18, ddc.getAssertionCount());
        assertEquals(2, ddc.getRoleWildCardCount());
        assertEquals(1, ddc.getDanglingRoles().size());
        assertEquals(2, ddc.getDanglingPolicies().size());
        assertNull(ddc.getProvidersWithoutTrust());
        assertNull(ddc.getTenantsWithoutAssumeRole());

        // delete the resource group tenancy support from sub domain
        // this means the tenant domain should show both the sub domain and
        // the top domain is without trust roles
        zms.deleteTenantResourceGroupRoles(mockDomRsrcCtx, provDomainSub, provSvc,
                tenantDomainName, "ravers", auditRef);

        ddc = zms.getDomainDataCheck(mockDomRsrcCtx, tenantDomainName);
        assertNotNull(ddc);
        assertEquals(11, ddc.getPolicyCount());
        assertEquals(18, ddc.getAssertionCount());
        assertEquals(2, ddc.getRoleWildCardCount());
        assertEquals(1, ddc.getDanglingRoles().size());
        assertEquals(2, ddc.getDanglingPolicies().size());
        assertEquals(1, ddc.getProvidersWithoutTrust().size());
        assertNull(ddc.getTenantsWithoutAssumeRole());

        // delete the dangling policies and dangling role
        zms.deletePolicy(mockDomRsrcCtx, tenantDomainName, "Policy2", auditRef);
        zms.deleteRole(mockDomRsrcCtx, tenantDomainName, "Role3", auditRef);
        zms.deleteRole(mockDomRsrcCtx, tenantDomainName, "Role2", auditRef);

        ddc = zms.getDomainDataCheck(mockDomRsrcCtx, tenantDomainName);
        assertNotNull(ddc);
        assertEquals(10, ddc.getPolicyCount());
        assertEquals(14, ddc.getAssertionCount());
        assertEquals(0, ddc.getRoleWildCardCount());
        assertNull(ddc.getDanglingRoles());
        assertNull(ddc.getDanglingPolicies());
        assertEquals(1, ddc.getProvidersWithoutTrust().size());
        assertNull(ddc.getTenantsWithoutAssumeRole());

        // add the tenancy support for top domain
        // - now tenant will see that it is all setup
        tenantRoles = new TenantResourceGroupRoles().setDomain(provDomainTop)
                .setService(provSvcTop).setTenant(tenantDomainName)
                .setRoles(roleActions).setResourceGroup("set1");

        zms.putTenantResourceGroupRoles(mockDomRsrcCtx, provDomainTop, provSvcTop,
                tenantDomainName, "set1", auditRef, tenantRoles);

        ddc = zms.getDomainDataCheck(mockDomRsrcCtx, tenantDomainName);
        assertNotNull(ddc);
        assertEquals(10, ddc.getPolicyCount());
        assertEquals(14, ddc.getAssertionCount());
        assertEquals(0, ddc.getRoleWildCardCount());
        assertNull(ddc.getDanglingRoles());
        assertNull(ddc.getDanglingPolicies());
        assertEquals(1, ddc.getProvidersWithoutTrust().size());
        assertNull(ddc.getTenantsWithoutAssumeRole());

        // delete the provider resource group roles for the sub domain provider
        // then everything in sync for this tenant
        zms.deleteProviderResourceGroupRoles(mockDomRsrcCtx, tenantDomainName, provDomainSub, provSvc,
                "ravers", auditRef);

        ddc = zms.getDomainDataCheck(mockDomRsrcCtx, tenantDomainName);
        assertNotNull(ddc);
        assertEquals(7, ddc.getPolicyCount());
        assertEquals(11, ddc.getAssertionCount());
        assertEquals(0, ddc.getRoleWildCardCount());
        assertNull(ddc.getDanglingRoles());
        assertNull(ddc.getDanglingPolicies());
        assertNull(ddc.getProvidersWithoutTrust());
        assertNull(ddc.getTenantsWithoutAssumeRole());

        zms.deleteTopLevelDomain(mockDomRsrcCtx, tenantDomainName, auditRef);
        zms.deleteSubDomain(mockDomRsrcCtx, provDomainTop, "sub", auditRef);
        zms.deleteTopLevelDomain(mockDomRsrcCtx, provDomainTop, auditRef);
    }

    @Test
    public void testGetServicePrincipal() {
        
        PrivateKey privateKey = Crypto.loadPrivateKey(Crypto.ybase64DecodeString(privKey));
        SimpleServiceIdentityProvider provider = new SimpleServiceIdentityProvider("coretech",
                "storage", privateKey, "0");
        
        Principal testPrincipal = provider.getIdentity("coretech", "storage");
        assertNotNull(testPrincipal);
        ResourceContext rsrcCtxTest = createResourceContext(testPrincipal);
        ServicePrincipal principal = zms.getServicePrincipal(rsrcCtxTest);
        assertNotNull(principal);
        assertEquals("storage", principal.getService());
        assertEquals("coretech", principal.getDomain());
    }

    @Test
    public void testGetServicePrincipalAuthorityNoAuthz() {

        PrivateKey privateKey = Crypto.loadPrivateKey(Crypto.ybase64DecodeString(privKey));
        Authority authority = new UserAuthority();
        SimpleServiceIdentityProvider provider = new SimpleServiceIdentityProvider(authority,
                "user", "test1", privateKey, "0", 3600);

        Principal testPrincipal = provider.getIdentity("user", "test1");
        assertNotNull(testPrincipal);
        ResourceContext rsrcCtxTest = createResourceContext(testPrincipal);
        ServicePrincipal principal = zms.getServicePrincipal(rsrcCtxTest);
        assertNotNull(principal);
        assertEquals("test1", principal.getService());
        assertEquals("user", principal.getDomain());
    }

    @Test
    public void testEmitMonmetricError() {
        int errorCode = 403;
        String caller = "forbiddenError";
        boolean isEmitMonmetricError;

        // negative tests
        isEmitMonmetricError = ZMSUtils.emitMonmetricError(errorCode, null);
        assertFalse(isEmitMonmetricError);

        isEmitMonmetricError = ZMSUtils.emitMonmetricError(errorCode, "");
        assertFalse(isEmitMonmetricError);

        isEmitMonmetricError = ZMSUtils.emitMonmetricError(0, caller);
        assertFalse(isEmitMonmetricError);

        isEmitMonmetricError = ZMSUtils.emitMonmetricError(-100, caller);
        assertFalse(isEmitMonmetricError);

        // positive tests
        isEmitMonmetricError = ZMSUtils.emitMonmetricError(errorCode, caller);
        assertTrue(isEmitMonmetricError);

        isEmitMonmetricError = ZMSUtils.emitMonmetricError(errorCode, " " + caller + " ");
        assertTrue(isEmitMonmetricError);
    }
    
    @Test
    public void testCheckKerberosAuthorityAuthorization() {
        Authority authority = new com.yahoo.athenz.auth.impl.KerberosAuthority();
        Principal principal = SimplePrincipal.create("krb", "user1", "v=U1;d=user;n=user1;s=signature",
                0, authority);
        assertNotNull(principal);
        assertTrue(zms.authorityAuthorizationAllowed(principal));
    }
    
    @Test
    public void testCheckNullAuthorityAuthorization() {
        Principal principal = SimplePrincipal.create("user", "joe", "v=U1;d=user;n=user1;s=signature",
                0, null);
        assertNotNull(principal);
        assertTrue(zms.authorityAuthorizationAllowed(principal));
    }
    
    @Test
    public void testValidateRoleBasedAccessCheckTrustDomain() {
        assertFalse(zms.validateRoleBasedAccessCheck(Collections.emptyList(), "trustdomain",
                "domain1", "domain1"));
    }
    
    @Test
    public void testValidateRoleBasedAccessCheckMismatchNames() {

        List<String> roles = new ArrayList<>();
        roles.add("readers");
        assertFalse(zms.validateRoleBasedAccessCheck(roles, null, "domain1", "domain2"));

        roles = new ArrayList<>();
        roles.add("domain1:role.readers");
        roles.add("domain2:role.readers");
        assertFalse(zms.validateRoleBasedAccessCheck(roles, null, "domain1", "domain1"));
    }
    
    @Test
    public void testValidateRoleBasedAccessCheckValid() {
        assertTrue(zms.validateRoleBasedAccessCheck(Collections.emptyList(), null, "domain1", "domain1"));

        List<String> roles = new ArrayList<>();
        roles.add("readers");
        assertTrue(zms.validateRoleBasedAccessCheck(roles, null, "domain1", "domain1"));

        roles = new ArrayList<>();
        roles.add("domain1:role.readers");
        roles.add("domain1:role.writers");
        assertTrue(zms.validateRoleBasedAccessCheck(roles, null, "domain1", "domain1"));
        assertTrue(zms.validateRoleBasedAccessCheck(roles, null, "domain1", "domain2"));
    }
    
    @Test
    public void testIsVirtualDomain() {
        
        assertTrue(zms.isVirtualDomain("user.user1"));
        assertTrue(zms.isVirtualDomain("user.user2"));
        assertTrue(zms.isVirtualDomain("user.user1.sub1"));
        assertTrue(zms.isVirtualDomain("user.user1.sub2.sub3"));
        
        assertFalse(zms.isVirtualDomain("user"));
        assertFalse(zms.isVirtualDomain("usertest"));
        assertFalse(zms.isVirtualDomain("coretech.api"));
    }

    @Test
    public void testHasExceededVirtualSubDomainLimitUnderLimitOneLevel() {
        
        System.setProperty(ZMSConsts.ZMS_PROP_VIRTUAL_DOMAIN_LIMIT, "2");
        ZMSImpl zmsTest = zmsInit();

        SubDomain dom = createSubDomainObject("user1", "user",
                "Test Domain", "testOrg", adminUser);
        Domain resDom = zmsTest.postSubDomain(mockDomRsrcCtx, "user", auditRef, dom);
        assertNotNull(resDom);

        assertFalse(zmsTest.hasExceededVirtualSubDomainLimit("user.user1"));
        
        dom = createSubDomainObject("sub1", "user.user1",
                "Test Domain2", "testOrg", adminUser);
        resDom = zms.postSubDomain(mockDomRsrcCtx, "user.user1", auditRef, dom);
        assertNotNull(resDom);
        
        assertFalse(zmsTest.hasExceededVirtualSubDomainLimit("user.user1"));
        
        zms.deleteSubDomain(mockDomRsrcCtx, "user.user1", "sub1", auditRef);
        zms.deleteSubDomain(mockDomRsrcCtx, "user", "user1", auditRef);
        System.clearProperty(ZMSConsts.ZMS_PROP_VIRTUAL_DOMAIN_LIMIT);
    }
    
    @Test
    public void testHasExceededVirtualSubDomainLimitOverLimitOneLevel() {
        
        System.setProperty(ZMSConsts.ZMS_PROP_VIRTUAL_DOMAIN_LIMIT, "2");
        ZMSImpl zmsTest = zmsInit();

        SubDomain dom = createSubDomainObject("user1", "user",
                "Test Domain", "testOrg", adminUser);
        Domain resDom = zmsTest.postSubDomain(mockDomRsrcCtx, "user", auditRef, dom);
        assertNotNull(resDom);

        dom = createSubDomainObject("sub1", "user.user1",
                "Test Domain2", "testOrg", adminUser);
        resDom = zms.postSubDomain(mockDomRsrcCtx, "user.user1", auditRef, dom);
        assertNotNull(resDom);
        
        dom = createSubDomainObject("sub2", "user.user1",
                "Test Domain2", "testOrg", adminUser);
        resDom = zms.postSubDomain(mockDomRsrcCtx, "user.user1", auditRef, dom);
        assertNotNull(resDom);
        
        assertTrue(zmsTest.hasExceededVirtualSubDomainLimit("user.user1"));
        
        zms.deleteSubDomain(mockDomRsrcCtx, "user.user1", "sub1", auditRef);
        zms.deleteSubDomain(mockDomRsrcCtx, "user.user1", "sub2", auditRef);
        zms.deleteSubDomain(mockDomRsrcCtx, "user", "user1", auditRef);
        System.clearProperty(ZMSConsts.ZMS_PROP_VIRTUAL_DOMAIN_LIMIT);
    }
    
    @Test
    public void testHasExceededVirtualSubDomainLimitUnderLimitMultipleLevel() {
        
        System.setProperty(ZMSConsts.ZMS_PROP_VIRTUAL_DOMAIN_LIMIT, "3");
        ZMSImpl zmsTest = zmsInit();

        SubDomain dom = createSubDomainObject("user1", "user",
                "Test Domain", "testOrg", adminUser);
        Domain resDom = zmsTest.postSubDomain(mockDomRsrcCtx, "user", auditRef, dom);
        assertNotNull(resDom);

        assertFalse(zmsTest.hasExceededVirtualSubDomainLimit("user.user1"));
        
        dom = createSubDomainObject("sub1", "user.user1",
                "Test Domain2", "testOrg", adminUser);
        resDom = zms.postSubDomain(mockDomRsrcCtx, "user.user1", auditRef, dom);
        assertNotNull(resDom);
        
        dom = createSubDomainObject("sub2", "user.user1.sub1",
                "Test Domain2", "testOrg", adminUser);
        resDom = zms.postSubDomain(mockDomRsrcCtx, "user.user1.sub1", auditRef, dom);
        assertNotNull(resDom);
        
        assertFalse(zmsTest.hasExceededVirtualSubDomainLimit("user.user1.sub1"));
        
        zms.deleteSubDomain(mockDomRsrcCtx, "user.user1.sub1", "sub2", auditRef);
        zms.deleteSubDomain(mockDomRsrcCtx, "user.user1", "sub1", auditRef);
        zms.deleteSubDomain(mockDomRsrcCtx, "user", "user1", auditRef);
        System.clearProperty(ZMSConsts.ZMS_PROP_VIRTUAL_DOMAIN_LIMIT);
    }
    
    @Test
    public void testHasExceededVirtualSubDomainLimitOverLimitMultipleLevel() {
        
        System.setProperty(ZMSConsts.ZMS_PROP_VIRTUAL_DOMAIN_LIMIT, "2");
        ZMSImpl zmsTest = zmsInit();

        SubDomain dom = createSubDomainObject("user1", "user",
                "Test Domain", "testOrg", adminUser);
        Domain resDom = zmsTest.postSubDomain(mockDomRsrcCtx, "user", auditRef, dom);
        assertNotNull(resDom);

        dom = createSubDomainObject("sub1", "user.user1",
                "Test Domain2", "testOrg", adminUser);
        resDom = zms.postSubDomain(mockDomRsrcCtx, "user.user1", auditRef, dom);
        assertNotNull(resDom);
        
        dom = createSubDomainObject("sub2", "user.user1.sub1",
                "Test Domain2", "testOrg", adminUser);
        resDom = zms.postSubDomain(mockDomRsrcCtx, "user.user1.sub1", auditRef, dom);
        assertNotNull(resDom);
        
        assertTrue(zmsTest.hasExceededVirtualSubDomainLimit("user.user1.sub1"));
        assertTrue(zmsTest.hasExceededVirtualSubDomainLimit("user.user1"));
        
        zms.deleteSubDomain(mockDomRsrcCtx, "user.user1.sub1", "sub2", auditRef);
        zms.deleteSubDomain(mockDomRsrcCtx, "user.user1", "sub1", auditRef);
        zms.deleteSubDomain(mockDomRsrcCtx, "user", "user1", auditRef);
        System.clearProperty(ZMSConsts.ZMS_PROP_VIRTUAL_DOMAIN_LIMIT);
    }

    @Test
    public void testGetNormalizedMemberNoSplit() {
        
        assertEquals(zms.normalizeDomainAliasUser("user.user"), "user.user");
        assertEquals(zms.normalizeDomainAliasUser("user.user2"), "user.user2");
        assertEquals(zms.normalizeDomainAliasUser("user.user1"), "user.user1");
        assertEquals(zms.normalizeDomainAliasUser("coretech.storage"), "coretech.storage");
        assertEquals(zms.normalizeDomainAliasUser("user1"), "user1");
    }
    
    @Test
    public void testGetNormalizedMemberInvalidFormat() {
        
        assertEquals(zms.normalizeDomainAliasUser("user:user:user1"), "user:user:user1");
        assertEquals(zms.normalizeDomainAliasUser("user:"), "user:");
        assertEquals(zms.normalizeDomainAliasUser("coretech:storage:api"), "coretech:storage:api");
    }
    
    @Test
    public void testGetNormalizedMemberUsersWithSplit() {
        
        assertEquals(zms.normalizeDomainAliasUser("user.user"), "user.user");
        assertEquals(zms.normalizeDomainAliasUser("user.user2"), "user.user2");
        assertEquals(zms.normalizeDomainAliasUser("user.user1"), "user.user1");
    }
    
    @Test
    public void testGetNormalizedMemberServiceWithSplit() {
        
        assertEquals(zms.normalizeDomainAliasUser("coretech.storage"), "coretech.storage");
        assertEquals(zms.normalizeDomainAliasUser("weather.storage.api"), "weather.storage.api");
        assertEquals(zms.normalizeDomainAliasUser("weather.entity.api"), "weather.entity.api");
        assertEquals(zms.normalizeDomainAliasUser("weather.storage.service.*"), "weather.storage.service.*");
    }
    
    @Test
    public void testGetNormalizedMemberAliasDomain() {
        
        ZMSImpl zmsImpl = zmsInit();
        zmsImpl.userDomain = "user";
        zmsImpl.userDomainPrefix = "user.";
        zmsImpl.userDomainAlias = null;
        zmsImpl.userDomainAliasPrefix = null;
        
        assertEquals(zmsImpl.normalizeDomainAliasUser("user-alias.user1"), "user-alias.user1");
        assertEquals(zmsImpl.normalizeDomainAliasUser("user-alias.user1.svc"), "user-alias.user1.svc");
        assertEquals(zmsImpl.normalizeDomainAliasUser("user.user1"), "user.user1");
        assertEquals(zmsImpl.normalizeDomainAliasUser("user.user1.svc"), "user.user1.svc");
        
        zmsImpl.userDomainAlias = "user-alias";
        zmsImpl.userDomainAliasPrefix = "user-alias.";
        assertEquals(zmsImpl.normalizeDomainAliasUser("user-alias.user1"), "user.user1");
        assertEquals(zmsImpl.normalizeDomainAliasUser("user-alias.user1.svc"), "user-alias.user1.svc");
        assertEquals(zmsImpl.normalizeDomainAliasUser("user.user1"), "user.user1");
        assertEquals(zmsImpl.normalizeDomainAliasUser("user.user1.svc"), "user.user1.svc");

        zmsImpl.objectStore.clearConnections();
    }
    
    @Test
    public void testNormalizeRoleMembersCombined() {

        ArrayList<RoleMember> roleMembers = new ArrayList<>();
        roleMembers.add(new RoleMember().setMemberName("user.joe"));
        roleMembers.add(new RoleMember().setMemberName("user.joe"));
        roleMembers.add(new RoleMember().setMemberName("user.joe"));
        roleMembers.add(new RoleMember().setMemberName("user.jane"));
        roleMembers.add(new RoleMember().setMemberName("coretech.storage"));
        roleMembers.add(new RoleMember().setMemberName("coretech.storage"));
        roleMembers.add(new RoleMember().setMemberName("weather.storage"));
        roleMembers.add(new RoleMember().setMemberName("weather.api.access"));

        ArrayList<String> membersList = new ArrayList<>();
        membersList.add("coretech.storage");
        membersList.add("user.john");

        Role role = createRoleObject("TestRole", "Role1", null, roleMembers);
        role.setMembers(membersList);

        zms.normalizeRoleMembers(role);

        List<RoleMember> members = role.getRoleMembers();
        assertNotNull(members);
        assertEquals(members.size(), 6);
        
        List<String> checkList = new ArrayList<>();
        checkList.add("user.joe");
        checkList.add("user.jane");
        checkList.add("user.john");
        checkList.add("weather.api.access");
        checkList.add("coretech.storage");
        checkList.add("weather.storage");
        checkRoleMember(checkList, members);
    }
    
    @Test
    public void testNormalizeRoleMembersInvalid() {

        ArrayList<RoleMember> roleMembers = new ArrayList<>();
        roleMembers.add(new RoleMember().setMemberName("user.joe"));
        roleMembers.add(new RoleMember().setMemberName("user2"));
        
        Role role = createRoleObject("TestRole", "Role1", null, roleMembers);
        zms.normalizeRoleMembers(role);
        
        List<RoleMember> members = role.getRoleMembers();
        assertNotNull(members);
        assertEquals(members.size(), 2);
        
        List<String> checkList = new ArrayList<>();
        checkList.add("user.joe");
        checkList.add("user2");
        checkRoleMember(checkList, members);
    }
    
    @Test
    public void testHasExceededListLimitNullLimit() {
        assertFalse(zms.hasExceededListLimit(null, 10));
    }
    
    @Test
    public void testHasExceededListLimitNotValidLimit() {
        assertFalse(zms.hasExceededListLimit(0, 10));
        assertFalse(zms.hasExceededListLimit(-1, 10));
    }
    
    @Test
    public void testHasExceededListLimitYes() {
        assertTrue(zms.hasExceededListLimit(10, 11));
    }
    
    @Test
    public void testHasExceededListLimitNo() {
        assertFalse(zms.hasExceededListLimit(10, 9));
        assertFalse(zms.hasExceededListLimit(10, 10));
    }
    
    @Test
    public void testVerifyServicePublicKeysNoKeys() {
        
        ServiceIdentity service = new ServiceIdentity();
        service.setName(ZMSUtils.serviceResourceName("ServiceAddInvalidCertDom1", "Service1"));

        // New Service need not have any public keys
        assertTrue(zms.verifyServicePublicKeys(service));
    }
    
    @Test
    public void testVerifyServicePublicKeysInvalidPublicKeys() {
        
        ServiceIdentity service = new ServiceIdentity();
        service.setName(ZMSUtils.serviceResourceName("ServiceDom1", "Service1"));
        
        List<PublicKeyEntry> publicKeyList = new ArrayList<>();
        PublicKeyEntry publicKeyEntry1 = new PublicKeyEntry();
        publicKeyEntry1.setKey("LS0tLS1CRUdJTiBQVUJMSUMgS0VZLS0tLS0KTUlHZk1BMEdDU3FHU0liM0RRRUJBUVVBQTRHTk");
        publicKeyEntry1.setId("1");
        publicKeyList.add(publicKeyEntry1);
        service.setPublicKeys(publicKeyList);
        
        assertFalse(zms.verifyServicePublicKeys(service));
    }
    
    @Test
    public void testVerifyServicePublicKeyInvalidPublicKey() {
        assertFalse(zms.verifyServicePublicKey("LS0tLS1CRUdJTiBQVUJMSUMgS0VZLS0tLS0KTUlHZk1B"));
        assertFalse(zms.verifyServicePublicKey("LS0tLS1CRUdJTiBQVUJMSUMgS0VZLS0tLS0KTUlHZk1BMEdDU3FHU0liM0RRRUJBUVVBQTRHTk"));
        assertFalse(zms.verifyServicePublicKey(privKeyK1));
        assertFalse(zms.verifyServicePublicKey(privKeyK2));
    }
    
    @Test
    public void testVerifyServicePublicKeyValidPublicKey() {
        assertTrue(zms.verifyServicePublicKey(pubKeyK1));
        assertTrue(zms.verifyServicePublicKey(pubKeyK2));
    }
    
    @Test
    public void testVerifyServicePublicKeysValidKeysOnly() {
        ServiceIdentity service = createServiceObject("ServiceAddDom1",
                "Service1", "http://localhost", "/usr/bin/java", "root",
                "users", "host1");
        assertTrue(zms.verifyServicePublicKeys(service));
    }

    @Test
    public void testShouldRunDelegatedTrustCheckNullTrust() {
        assertFalse(zms.shouldRunDelegatedTrustCheck(null, "TrustDomain"));
    }
    @Test
    public void testShouldRunDelegatedTrustCheckNullTrustDomain() {
        assertTrue(zms.shouldRunDelegatedTrustCheck("TrustDomain", null));
    }
    @Test
    public void testShouldRunDelegatedTrustCheckMatch() {
        assertTrue(zms.shouldRunDelegatedTrustCheck("TrustDomain", "TrustDomain"));
    }
    @Test
    public void testShouldRunDelegatedTrustCheckNoMatch() {
        assertFalse(zms.shouldRunDelegatedTrustCheck("TrustDomain1", "TrustDomain"));
    }

    @Test
    public void testIsValidUserTokenRequestNoAuthority() {
        Principal principal = SimplePrincipal.create("user", "user1", "v=U1;d=user;n=user1;s=signature");
        assertFalse(zms.isValidUserTokenRequest(principal, "user1"));
    }
    
    @Test
    public void testIsValidUserTokenRequestNotuserAuthority() {
        Authority principalAuthority = new com.yahoo.athenz.common.server.debug.DebugPrincipalAuthority();
        Principal principal = SimplePrincipal.create("user", "user1", "v=U1;d=user;n=user1;s=signature",
                0, principalAuthority);
        
        assertFalse(zms.isValidUserTokenRequest(principal, "user1"));
    }
    
    @Test
    public void testIsValidUserTokenRequestNullPrincipal() {
        assertFalse(zms.isValidUserTokenRequest(null, "user1"));
    }
    
    @Test
    public void testMatchDelegatedTrustPolicyNullAssertions() {
        Policy policy = new Policy();
        assertFalse(zms.matchDelegatedTrustPolicy(policy, "testRole", "testMember", null));
    }
    
    @Test
    public void testMatchDelegatedTrustAssertionInvalidAction() {
        
        Assertion assertion = new Assertion();
        assertion.setAction("READ");
        assertion.setEffect(AssertionEffect.ALLOW);
        assertion.setResource("domain:*");
        assertion.setRole("domain:role.Role");

        assertFalse(zms.matchDelegatedTrustAssertion(assertion, null, null, null));
    }
    
    @Test
    public void testMatchDelegatedTrustAssertionNoResPatternMatchWithOutPattern() {
        
        Assertion assertion = new Assertion();
        assertion.setAction("ASSUME_ROLE");
        assertion.setEffect(AssertionEffect.ALLOW);
        assertion.setResource("domain:role.Role");
        assertion.setRole("domain:role.Role");

        assertFalse(zms.matchDelegatedTrustAssertion(assertion, "domain:role.Role2", null, null));
        assertFalse(zms.matchDelegatedTrustAssertion(assertion, "coretech:role.Role", null, null));
    }
    
    @Test
    public void testMatchDelegatedTrustAssertionNoResPatternMatchWithPattern() {
        
        Assertion assertion = new Assertion();
        assertion.setAction("ASSUME_ROLE");
        assertion.setEffect(AssertionEffect.ALLOW);
        assertion.setResource("*:role.Role");
        assertion.setRole("domain:role.Role");

        assertFalse(zms.matchDelegatedTrustAssertion(assertion, "domain:role.Role2", null, null));
        assertFalse(zms.matchDelegatedTrustAssertion(assertion, "coretech:role.Role2", null, null));
    }
    
    @Test
    public void testMatchDelegatedTrustAssertionNoRoleMatchWithPattern() {
        
        Assertion assertion = new Assertion();
        assertion.setAction("ASSUME_ROLE");
        assertion.setEffect(AssertionEffect.ALLOW);
        assertion.setResource("*:role.Role");
        assertion.setRole("weather:role.*");
        
        List<Role> roles = new ArrayList<>();
        
        Role role = createRoleObject("coretech",  "readers", null);
        roles.add(role);

        role = createRoleObject("coretech",  "writers", null);
        roles.add(role);

        role = createRoleObject("coretech",  "updaters", null);
        roles.add(role);
        
        assertFalse(zms.matchDelegatedTrustAssertion(assertion, "coretech:role.Role", null, roles));
    }
    
    @Test
    public void testMatchDelegatedTrustAssertionNoRoleMatchWithOutPattern() {
        
        Assertion assertion = new Assertion();
        assertion.setAction("ASSUME_ROLE");
        assertion.setEffect(AssertionEffect.ALLOW);
        assertion.setResource("*:role.Role");
        assertion.setRole("weather:role.Role");
        
        List<Role> roles = new ArrayList<>();
        
        Role role = createRoleObject("coretech",  "Role1", null);
        roles.add(role);

        role = createRoleObject("coretech",  "Role2", null);
        roles.add(role);
        
        assertFalse(zms.matchDelegatedTrustAssertion(assertion, "weather:role.Role1", null, roles));
        assertFalse(zms.matchDelegatedTrustAssertion(assertion, "coretech:role.Role", null, roles));
    }
    
    @Test
    public void testMatchDelegatedTrustAssertionNoMemberMatch() {
        
        Assertion assertion = new Assertion();
        assertion.setAction("ASSUME_ROLE");
        assertion.setEffect(AssertionEffect.ALLOW);
        assertion.setResource("*:role.Role");
        assertion.setRole("weather:role.Role");
        
        List<Role> roles = new ArrayList<>();
        
        Role role = createRoleObject("weather",  "Role1", null, "user.user1", null);
        roles.add(role);

        role = createRoleObject("weather",  "Role", null, "user.user2", null);
        roles.add(role);
        
        assertFalse(zms.matchDelegatedTrustAssertion(assertion, "weather:role.Role", "user.user1", roles));
    }
    
    @Test
    public void testMatchDelegatedTrustAssertionValidWithPattern() {
        
        Assertion assertion = new Assertion();
        assertion.setAction("ASSUME_ROLE");
        assertion.setEffect(AssertionEffect.ALLOW);
        assertion.setResource("*:role.Role");
        assertion.setRole("weather:role.*");
        
        List<Role> roles = new ArrayList<>();
        
        Role role = createRoleObject("weather",  "Role1", null, "user.user1", null);
        roles.add(role);

        role = createRoleObject("weather",  "Role", null, "user.user2", null);
        roles.add(role);
        
        assertTrue(zms.matchDelegatedTrustAssertion(assertion, "weather:role.Role", "user.user2", roles));
    }
    
    @Test
    public void testMatchDelegatedTrustAssertionValidWithOutPattern() {
        
        Assertion assertion = new Assertion();
        assertion.setAction("ASSUME_ROLE");
        assertion.setEffect(AssertionEffect.ALLOW);
        assertion.setResource("*:role.Role");
        assertion.setRole("weather:role.Role");
        
        List<Role> roles = new ArrayList<>();
        
        Role role = createRoleObject("weather",  "Role1", null, "user.user1", null);
        roles.add(role);

        role = createRoleObject("weather",  "Role", null, "user.user2", null);
        roles.add(role);
        
        assertTrue(zms.matchDelegatedTrustAssertion(assertion, "weather:role.Role", "user.user2", roles));
    }
    
    @Test
    public void testMatchPrincipalInRoleStdMemberMatch() {
        
        Role role = createRoleObject("weather",  "Role", null, "user.user2", null);
        assertTrue(zms.matchPrincipalInRole(role, null, "user.user2", null));
    }
    
    @Test
    public void testMatchPrincipalInRoleStdMemberNoMatch() {
        
        Role role = createRoleObject("weather",  "Role", null, "user.user2", null);
        assertFalse(zms.matchPrincipalInRole(role, null, "user.user23", null));
    }
    
    @Test
    public void testMatchPrincipalInRoleNoDelegatedTrust() {
        Role role = createRoleObject("weather",  "Role", null);
        assertFalse(zms.matchPrincipalInRole(role, null, null, null));
        assertFalse(zms.matchPrincipalInRole(role, null, null, "weather"));
    }
    
    @Test
    public void testMatchPrincipalInRoleDelegatedTrustNoMatch() {
        Role role = createRoleObject("weather",  "Role", "coretech_not_present");
        assertFalse(zms.matchPrincipalInRole(role, "Role", "user.user1", "coretech_not_present"));
    }

    @Test
    public void testMatchPrincipalInRoleDelegatedTrustMatch() {

        String domainName = "coretechtrust";
        List<String> adminUsers = new ArrayList<>();
        adminUsers.add("user.user2");
        zms.dbService.makeDomain(mockDomRsrcCtx, ZMSTestUtils.makeDomainObject(domainName, "Test Domain", "org",
                true, null, 0, null, 0), adminUsers, null, auditRef);

        Policy policy = createPolicyObject(domainName, "trust", "coretechtrust:role.role1",
                false, "ASSUME_ROLE", "weather:role.role1", AssertionEffect.ALLOW);
        zms.dbService.executePutPolicy(mockDomRsrcCtx, domainName, "trust",
                policy, auditRef, "unitTest");
        
        Role role1 = createRoleObject(domainName,  "role1", null, "user.user1", null);
        zms.dbService.executePutRole(mockDomRsrcCtx, domainName, "role1",
                role1, auditRef, "unittest");

        Role role2 = createRoleObject(domainName,  "role2", null, "user.user2", null);
        zms.dbService.executePutRole(mockDomRsrcCtx, domainName, "role2",
                role2, auditRef, "unittest");
        
        Role role = createRoleObject("weather",  "role1", domainName);
        assertTrue(zms.matchPrincipalInRole(role, "weather:role.role1", "user.user1", "coretechtrust"));
        assertFalse(zms.matchPrincipalInRole(role, "weather:role.role1", "user.user1", "coretechtrust2"));
        assertFalse(zms.matchPrincipalInRole(role, "weather:role.role1", "user.user3", "coretechtrust"));
        zms.dbService.executeDeleteDomain(mockDomRsrcCtx, domainName, auditRef, "unittest");
    }
    
    @Test
    public void testProcessListRequestNoCollection() {
        
        String domainName = "listrequest";
        List<String> adminUsers = new ArrayList<>();
        adminUsers.add("user.user");
        zms.dbService.makeDomain(mockDomRsrcCtx, ZMSTestUtils.makeDomainObject(domainName, "Test Domain", "org",
                true, null, 0, null, 0), adminUsers, null, auditRef);
        
        Role role1 = createRoleObject(domainName,  "role1", null, "user.user", null);
        zms.dbService.executePutRole(mockDomRsrcCtx, domainName, "role1",
                role1, auditRef, "unittest");

        Role role2 = createRoleObject(domainName,  "role2", null, "user.user", null);
        zms.dbService.executePutRole(mockDomRsrcCtx, domainName, "role2",
                role2, auditRef, "unittest");
        
        Role role3 = createRoleObject(domainName,  "role3", null, "user.user", null);
        zms.dbService.executePutRole(mockDomRsrcCtx, domainName, "role3",
                role3, auditRef, "unittest");
        
        zms.dbService.executeDeletePolicy(mockDomRsrcCtx, domainName, "admin", auditRef, "unittest");
        
        List<String> names = new ArrayList<>();
        assertNull(zms.processListRequest(domainName, AthenzObject.POLICY, null, null, names));
        assertEquals(names.size(), 0);
        zms.dbService.executeDeleteDomain(mockDomRsrcCtx, domainName, auditRef, "unittest");
    }
    
    @Test
    public void testProcessListRequestCollectionEmpty() {
        
        String domainName = "listrequest";
        List<String> adminUsers = new ArrayList<>();
        adminUsers.add("user.user");
        zms.dbService.makeDomain(mockDomRsrcCtx, ZMSTestUtils.makeDomainObject(domainName, "Test Domain", "org",
                true, null, 0, null, 0), adminUsers, null, auditRef);
        
        zms.dbService.executeDeleteRole(mockDomRsrcCtx, domainName, "admin", auditRef, "unittest");
        
        List<String> names = new ArrayList<>();
        assertNull(zms.processListRequest(domainName, AthenzObject.ROLE, null, null, names));
        assertEquals(names.size(), 0);
        zms.dbService.executeDeleteDomain(mockDomRsrcCtx, domainName, auditRef, "unittest");
    }
    
    @Test
    public void testProcessListRequestUnknownType() {
        
        List<String> names = new ArrayList<>();
        assertNull(zms.processListRequest("testdomain", AthenzObject.ASSERTION, null, null, names));
        assertEquals(names.size(), 0);
    }
    
    @Test
    public void testProcessListRequestSkipNoMatch() {
        
        String domainName = "listrequestskipnomatch";
        List<String> adminUsers = new ArrayList<>();
        adminUsers.add("user.user");
        zms.dbService.makeDomain(mockDomRsrcCtx, ZMSTestUtils.makeDomainObject(domainName, "Test Domain", "org",
                true, null, 0, null, 0), adminUsers, null, auditRef);
        
        Role role1 = createRoleObject(domainName,  "role1", null, "user.user", null);
        zms.dbService.executePutRole(mockDomRsrcCtx, domainName, "role1",
                role1, auditRef, "unittest");

        Role role2 = createRoleObject(domainName,  "role2", null, "user.user", null);
        zms.dbService.executePutRole(mockDomRsrcCtx, domainName, "role2",
                role2, auditRef, "unittest");
        
        Role role3 = createRoleObject(domainName,  "role3", null, "user.user", null);
        zms.dbService.executePutRole(mockDomRsrcCtx, domainName, "role3",
                role3, auditRef, "unittest");
        
        List<String> names = new ArrayList<>();
        assertNull(zms.processListRequest(domainName, AthenzObject.ROLE, null, "role4", names));
        
        // our response is going to get the admin role
        
        assertEquals(4, names.size());
        assertTrue(names.contains("admin"));
        assertTrue(names.contains("role1"));
        assertTrue(names.contains("role2"));
        assertTrue(names.contains("role3"));
        zms.dbService.executeDeleteDomain(mockDomRsrcCtx, domainName, auditRef, "unittest");
    }
    
    @Test
    public void testProcessListRequestSkipMatch() {
        
        String domainName = "listrequestskipmatch";
        List<String> adminUsers = new ArrayList<>();
        adminUsers.add("user.user");
        zms.dbService.makeDomain(mockDomRsrcCtx, ZMSTestUtils.makeDomainObject(domainName, "Test Domain", "org",
                true, null, 0, null, 0), adminUsers, null, auditRef);
        
        Role role1 = createRoleObject(domainName,  "role1", null, "user.user", null);
        zms.dbService.executePutRole(mockDomRsrcCtx, domainName, "role1",
                role1, auditRef, "unittest");

        Role role2 = createRoleObject(domainName,  "role2", null, "user.user", null);
        zms.dbService.executePutRole(mockDomRsrcCtx, domainName, "role2",
                role2, auditRef, "unittest");
        
        Role role3 = createRoleObject(domainName,  "role3", null, "user.user", null);
        zms.dbService.executePutRole(mockDomRsrcCtx, domainName, "role3",
                role3, auditRef, "unittest");
        
        List<String> names = new ArrayList<>();
        assertNull(zms.processListRequest(domainName, AthenzObject.ROLE, null, "role2", names));
        assertEquals(names.size(), 1);
        assertTrue(names.contains("role3"));
        zms.dbService.executeDeleteDomain(mockDomRsrcCtx, domainName, auditRef, "unittest");
    }
    
    @Test
    public void testProcessListRequestLimitExceeded() {
        
        String domainName = "listrequestlimitexceeded";
        List<String> adminUsers = new ArrayList<>();
        adminUsers.add("user.user");
        zms.dbService.makeDomain(mockDomRsrcCtx, ZMSTestUtils.makeDomainObject(domainName, "Test Domain", "org",
                true, null, 0, null, 0), adminUsers, null, auditRef);
        
        Role role1 = createRoleObject(domainName,  "role1", null, "user.user", null);
        zms.dbService.executePutRole(mockDomRsrcCtx, domainName, "role1",
                role1, auditRef, "unittest");

        Role role2 = createRoleObject(domainName,  "role2", null, "user.user", null);
        zms.dbService.executePutRole(mockDomRsrcCtx, domainName, "role2",
                role2, auditRef, "unittest");
        
        Role role3 = createRoleObject(domainName,  "role3", null, "user.user", null);
        zms.dbService.executePutRole(mockDomRsrcCtx, domainName, "role3",
                role3, auditRef, "unittest");
        
        List<String> names = new ArrayList<>();
        String next = zms.processListRequest(domainName, AthenzObject.ROLE, 2, null, names);
        assertEquals("role1", next);
        assertEquals(2, names.size());
        assertTrue(names.contains("admin"));
        assertTrue(names.contains("role1"));
        zms.dbService.executeDeleteDomain(mockDomRsrcCtx, domainName, auditRef, "unittest");
    }
    
    @Test
    public void testProcessListRequestLimitNotExceeded() {

        String domainName = "listrequestlimitnotexceeded";
        List<String> adminUsers = new ArrayList<>();
        adminUsers.add("user.user");
        zms.dbService.makeDomain(mockDomRsrcCtx, ZMSTestUtils.makeDomainObject(domainName, "Test Domain", "org",
                true, null, 0, null, 0), adminUsers, null, auditRef);
        
        Role role1 = createRoleObject(domainName,  "role1", null, "user.user", null);
        zms.dbService.executePutRole(mockDomRsrcCtx, domainName, "role1",
                role1, auditRef, "unittest");

        Role role2 = createRoleObject(domainName,  "role2", null, "user.user", null);
        zms.dbService.executePutRole(mockDomRsrcCtx, domainName, "role2",
                role2, auditRef, "unittest");
        
        Role role3 = createRoleObject(domainName,  "role3", null, "user.user", null);
        zms.dbService.executePutRole(mockDomRsrcCtx, domainName, "role3",
                role3, auditRef, "unittest");
        
        List<String> names = new ArrayList<>();
        zms.processListRequest(domainName, AthenzObject.ROLE, 5, null, names);
        
        // make sure to account for the admin role
        
        assertEquals(4, names.size());
        assertTrue(names.contains("admin"));
        assertTrue(names.contains("role1"));
        assertTrue(names.contains("role2"));
        assertTrue(names.contains("role3"));
        zms.dbService.executeDeleteDomain(mockDomRsrcCtx, domainName, auditRef, "unittest");
    }
    
    @Test
    public void testProcessListRequestLimitAndSkip() {
        
        String domainName = "listrequestlimitandskip";
        List<String> adminUsers = new ArrayList<>();
        adminUsers.add("user.user");
        zms.dbService.makeDomain(mockDomRsrcCtx, ZMSTestUtils.makeDomainObject(domainName, "Test Domain", "org",
                true, null, 0, null, 0), adminUsers, null, auditRef);
        
        Role role1 = createRoleObject(domainName,  "role1", null, "user.user", null);
        zms.dbService.executePutRole(mockDomRsrcCtx, domainName, "role1",
                role1, auditRef, "unittest");

        Role role2 = createRoleObject(domainName,  "role2", null, "user.user", null);
        zms.dbService.executePutRole(mockDomRsrcCtx, domainName, "role2",
                role2, auditRef, "unittest");
        
        Role role3 = createRoleObject(domainName,  "role3", null, "user.user", null);
        zms.dbService.executePutRole(mockDomRsrcCtx, domainName, "role3",
                role3, auditRef, "unittest");
        
        Role role4 = createRoleObject(domainName,  "role4", null, "user.user", null);
        zms.dbService.executePutRole(mockDomRsrcCtx, domainName, "role4",
                role4, auditRef, "unittest");
        
        Role role5 = createRoleObject(domainName,  "role5", null, "user.user", null);
        zms.dbService.executePutRole(mockDomRsrcCtx, domainName, "role5",
                role5, auditRef, "unittest");
        
        List<String> names = new ArrayList<>();
        String next = zms.processListRequest(domainName, AthenzObject.ROLE, 2, "role2", names);
        assertEquals(next, "role4");
        assertEquals(names.size(), 2);
        assertTrue(names.contains("role3"));
        assertTrue(names.contains("role4"));
        zms.dbService.executeDeleteDomain(mockDomRsrcCtx, domainName, auditRef, "unittest");
    }
    
    @Test
    public void testProcessListRequestLimitAndSkipLessThanLimitLeft() {
        
        String domainName = "listrequestlimitskiplessthanlimitleft";
        List<String> adminUsers = new ArrayList<>();
        adminUsers.add("user.user");
        zms.dbService.makeDomain(mockDomRsrcCtx, ZMSTestUtils.makeDomainObject(domainName, "Test Domain", "org",
                true, null, 0, null, 0), adminUsers, null, auditRef);
        
        Role role1 = createRoleObject(domainName,  "role1", null, "user.user", null);
        zms.dbService.executePutRole(mockDomRsrcCtx, domainName, "role1",
                role1, auditRef, "unittest");

        Role role2 = createRoleObject(domainName,  "role2", null, "user.user", null);
        zms.dbService.executePutRole(mockDomRsrcCtx, domainName, "role2",
                role2, auditRef, "unittest");
        
        Role role3 = createRoleObject(domainName,  "role3", null, "user.user", null);
        zms.dbService.executePutRole(mockDomRsrcCtx, domainName, "role3",
                role3, auditRef, "unittest");
        
        Role role4 = createRoleObject(domainName,  "role4", null, "user.user", null);
        zms.dbService.executePutRole(mockDomRsrcCtx, domainName, "role4",
                role4, auditRef, "unittest");
        
        Role role5 = createRoleObject(domainName,  "role5", null, "user.user", null);
        zms.dbService.executePutRole(mockDomRsrcCtx, domainName, "role5",
                role5, auditRef, "unittest");
        
        List<String> names = new ArrayList<>();
        assertNull(zms.processListRequest(domainName, AthenzObject.ROLE, 2, "role4", names));
        assertEquals(names.size(), 1);
        assertTrue(names.contains("role5"));
        zms.dbService.executeDeleteDomain(mockDomRsrcCtx, domainName, auditRef, "unittest");
    }
    
    @Test
    public void testAccessInvalidResourceDomain() {
        Principal principal = SimplePrincipal.create("user", "user1", "v=U1;d=user;n=user1;s=signature");
        try {
            zms.access("read", "domain:invalid:entity", principal, null);
            fail();
        } catch (com.yahoo.athenz.common.server.rest.ResourceException ex) {
            assertEquals(ex.getCode(), 404);
        }
    }
    
    @Test
    public void testHasAccessInvalidRoleTokenAccess() {

        final String domainName = "coretech";
        TopLevelDomain dom = createTopLevelDomainObject(domainName,
                "Test Domain", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom);
        
        List<String> authRoles = new ArrayList<>();
        authRoles.add("role1");
        Principal principal = SimplePrincipal.create(domainName, "v=U1;d=user;n=user1;s=signature", authRoles, null);
        assertNotNull(principal);
        AthenzDomain domain = zms.retrieveAccessDomain(domainName, principal);
        assertEquals(zms.hasAccess(domain, "read", domainName + ":entity", principal, "trustdomain"),
                AccessStatus.DENIED_INVALID_ROLE_TOKEN);
        
        zms.deleteTopLevelDomain(mockDomRsrcCtx, domainName, auditRef);
    }
    
    @Test
    public void testAccessNotFoundDomain() {
        Principal principal = SimplePrincipal.create("user", "user1", "v=U1;d=user;n=user1;s=signature");
        try {
            zms.access("read", "domain_not_found:entity", principal, null);
            fail();
        } catch (com.yahoo.athenz.common.server.rest.ResourceException ex) {
            assertEquals(ex.getCode(), 404);
        }
    }
    
    @Test
    public void testHasAccessValidMember() {

        TopLevelDomain dom1 = createTopLevelDomainObject("HasAccessDom1",
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        Role role1 = createRoleObject("HasAccessDom1", "Role1", null, "user.user1",
                "user.user3");
        zms.putRole(mockDomRsrcCtx, "HasAccessDom1", "Role1", auditRef, role1);

        Policy policy1 = createPolicyObject("HasAccessDom1", "Policy1", "Role1",
                "UPDATE", "HasAccessDom1:resource1", AssertionEffect.ALLOW);
        zms.putPolicy(mockDomRsrcCtx, "HasAccessDom1", "Policy1", auditRef, policy1);

        // user1 and user3 have access to UPDATE/resource1

        Principal principal1 = SimplePrincipal.create("user", "user1", "v=U1;d=user;n=user1;s=signature");
        AthenzDomain domain = zms.retrieveAccessDomain("hasaccessdom1", principal1);

        assertEquals(zms.hasAccess(domain, "update", "hasaccessdom1:resource1",
                principal1, null), AccessStatus.ALLOWED);
        
        Principal principal3 = SimplePrincipal.create("user", "user3", "v=U1;d=user;n=user3;s=signature");
        assertEquals(zms.hasAccess(domain, "update", "hasaccessdom1:resource1",
                principal3, null), AccessStatus.ALLOWED);
        
        zms.deleteTopLevelDomain(mockDomRsrcCtx, "HasAccessDom1", auditRef);
    }
    
    @Test
    public void testHasAccessInValidMember() {

        TopLevelDomain dom1 = createTopLevelDomainObject("HasAccessDom2",
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        Role role1 = createRoleObject("HasAccessDom2", "Role1", null, "user.user1",
                "user.user3");
        zms.putRole(mockDomRsrcCtx, "HasAccessDom2", "Role1", auditRef, role1);

        Policy policy1 = createPolicyObject("HasAccessDom2", "Policy1", "Role1",
                "UPDATE", "HasAccessDom2:resource1", AssertionEffect.ALLOW);
        zms.putPolicy(mockDomRsrcCtx, "HasAccessDom2", "Policy1", auditRef, policy1);

        // user2 does not have access to UPDATE/resource1

        Principal principal2 = SimplePrincipal.create("user", "user2", "v=U1;d=user;n=user2;s=signature");
        
        // this is internal zms function so the values passed have already been converted to lower
        // case so we need to handle the test case accordingly.
        
        AthenzDomain domain = zms.retrieveAccessDomain("hasaccessdom2", principal2);
        assertEquals(AccessStatus.DENIED, zms.hasAccess(domain, "update",
                "hasaccessdom2:resource1", principal2, null));
        zms.deleteTopLevelDomain(mockDomRsrcCtx, "HasAccessDom2", auditRef);
    }
    
    @Test
    public void testEvaluateAccessNoAssertions() {
        
        AthenzDomain domain = new AthenzDomain("coretech");
        Role role = new Role().setName("coretech:role.role1");
        domain.getRoles().add(role);
        Policy policy = new Policy().setName("coretech:policy.policy1");
        domain.getPolicies().add(policy);
        assertEquals(zms.evaluateAccess(domain, null, null, null, null, null, mockDomRestRsrcCtx.principal()), AccessStatus.DENIED);
    }
    
    @Test
    public void testEvaluateAccessAssertionDeny() {
        
        AthenzDomain domain = new AthenzDomain("coretech");
        Role role = createRoleObject("coretech", "role1", null, "user.user1", null);
        domain.getRoles().add(role);

        Policy policy = new Policy().setName("coretech:policy.policy1");
        Assertion assertion = new Assertion();
        assertion.setAction("read");
        assertion.setEffect(AssertionEffect.DENY);
        assertion.setResource("coretech:*");
        assertion.setRole("coretech:role.role1");
        policy.setAssertions(new ArrayList<>());
        policy.getAssertions().add(assertion);
        domain.getPolicies().add(policy);
        
        assertEquals(zms.evaluateAccess(domain, "user.user1", "read", "coretech:resource1",
                null, null, mockDomRestRsrcCtx.principal()), AccessStatus.DENIED);
    }

    @Test
    public void testEvaluateAccessAssertionDenyCaseSensitive() {

        AthenzDomain domain = new AthenzDomain("coretech");
        Role role = createRoleObject("coretech", "role1", null, "user.user1", null);
        domain.getRoles().add(role);

        Policy policy = new Policy().setName("coretech:policy.policy1");
        Assertion assertion = new Assertion();
        assertion.setAction("ReaD");
        assertion.setEffect(AssertionEffect.DENY);
        assertion.setResource("coretech:*");
        assertion.setRole("coretech:role.role1");
        policy.setAssertions(new ArrayList<>());
        policy.getAssertions().add(assertion);
        domain.getPolicies().add(policy);

        ZMSImpl spiedZms = Mockito.spy(zms);
        assertEquals(spiedZms.evaluateAccess(domain, "user.user1", "read", "coretech:resource1",
                null, null, mockDomRestRsrcCtx.principal()), AccessStatus.DENIED);

        // Verify that it was denied by explicit "Deny" assertion and not because no match was found
        Mockito.verify(spiedZms, times(1)).matchPrincipal(
                eq(domain.getRoles()),
                eq("^coretech:role\\.role1$"),
                eq("user.user1"),
                eq(null));
    }

    @Test
    public void testEvaluateAccessAssertionAllow() {
        
        AthenzDomain domain = new AthenzDomain("coretech");
        Role role = createRoleObject("coretech", "role1", null, "user.user1", null);
        domain.getRoles().add(role);

        Policy policy = new Policy().setName("coretech:policy.policy1");
        Assertion assertion = new Assertion();
        assertion.setAction("read");
        assertion.setEffect(AssertionEffect.ALLOW);
        assertion.setResource("coretech:*");
        assertion.setRole("coretech:role.role1");
        policy.setAssertions(new ArrayList<>());
        policy.getAssertions().add(assertion);
        domain.getPolicies().add(policy);
        
        assertEquals(zms.evaluateAccess(domain, "user.user1", "read", "coretech:resource1", null, null, mockDomRestRsrcCtx.principal()), AccessStatus.ALLOWED);
    }

    @Test
    public void testEvaluateAccessMtlsRestricted() {

        AthenzDomain domain = new AthenzDomain("coretech");
        Role role = createRoleObject("coretech", "role1", null, "user.user1", null);
        domain.getRoles().add(role);

        Policy policy = new Policy().setName("coretech:policy.policy1");
        Assertion assertion = new Assertion();
        assertion.setAction("read");
        assertion.setEffect(AssertionEffect.ALLOW);
        assertion.setResource("coretech:*");
        assertion.setRole("coretech:role.role1");
        policy.setAssertions(new ArrayList<>());
        policy.getAssertions().add(assertion);
        domain.getPolicies().add(policy);

        Authority certificateAuthority = new CertificateAuthority();
        String unsignedCreds = "v=U1;d=user;n=user2";
        final Principal rsrcPrince = SimplePrincipal.create("user", "user2",
                unsignedCreds + ";s=signature", 0, certificateAuthority);

        assertEquals(zms.evaluateAccess(domain, "user.user1", "read", "coretech:resource1", null, null, rsrcPrince), AccessStatus.ALLOWED);
        ((SimplePrincipal)rsrcPrince).setMtlsRestricted(true);
        assertEquals(zms.evaluateAccess(domain, "user.user1", "read", "coretech:resource1", null, null, rsrcPrince), AccessStatus.DENIED);
    }

    @Test
    public void testEvaluateAccessAssertionAllowCaseSensitive() {

        AthenzDomain domain = new AthenzDomain("coretech");
        Role role = createRoleObject("coretech", "role1", null, "user.user1", null);
        domain.getRoles().add(role);

        Policy policy = new Policy().setName("coretech:policy.policy1");
        Assertion assertion = new Assertion();
        assertion.setAction("ReaD");
        assertion.setEffect(AssertionEffect.ALLOW);
        assertion.setResource("coretech:*");
        assertion.setRole("coretech:role.role1");
        policy.setAssertions(new ArrayList<>());
        policy.getAssertions().add(assertion);
        domain.getPolicies().add(policy);

        assertEquals(zms.evaluateAccess(domain, "user.user1", "read", "coretech:resource1", null, null, mockDomRestRsrcCtx.principal()), AccessStatus.ALLOWED);
    }

    @Test
    public void testHasExceededDepthLimitNullLimit() {
        assertFalse(zms.hasExceededDepthLimit(null, "domain"));
    }
    
    @Test
    public void testHasExceededDepthLimitNotValidLimit() {
        assertTrue(zms.hasExceededDepthLimit(-1, "domain"));
        assertTrue(zms.hasExceededDepthLimit(-1, "domain.sub1"));
    }
    
    @Test
    public void testHasExceededDepthLimitYes() {
        assertTrue(zms.hasExceededDepthLimit(0, "domain.sub1"));
        assertTrue(zms.hasExceededDepthLimit(1, "domain.sub1.sub2"));
        assertTrue(zms.hasExceededDepthLimit(1, "domain.sub1.sub2.sub3"));
        assertTrue(zms.hasExceededDepthLimit(2, "domain.sub1.sub2.sub3"));
    }
    
    @Test
    public void testHasExceededDepthLimitNo() {
        assertFalse(zms.hasExceededDepthLimit(1, "domain.sub1"));
        assertFalse(zms.hasExceededDepthLimit(2, "domain.sub1"));
        assertFalse(zms.hasExceededDepthLimit(2, "domain.sub1.sub2"));
        assertFalse(zms.hasExceededDepthLimit(3, "domain.sub1.sub2"));
        assertFalse(zms.hasExceededDepthLimit(3, "domain.sub1.sub2.sub3"));
        assertFalse(zms.hasExceededDepthLimit(4, "domain.sub1.sub2.sub3"));
    }

    @Test
    public void testIsZMSServiceYes() {
        
        assertTrue(zms.isZMSService("sys.auth", "zms"));
        assertTrue(zms.isZMSService("sys.Auth", "ZMS"));
        assertTrue(zms.isZMSService("SYS.AUTH", "ZMS"));
    }
    
    @Test
    public void testIsZMSServiceNo() {
        
        assertFalse(zms.isZMSService("sys.auth2", "zms"));
        assertFalse(zms.isZMSService("sys.auth", "zts"));
    }
    
    @Test
    public void testRetrieveServiceIdentityInvalidServiceName() {
        
        TopLevelDomain dom1 = createTopLevelDomainObject("ServiceRetrieveDom1",
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        ServiceIdentity service = createServiceObject("ServiceRetrieveDom1",
                "Service1", "http://localhost", "/usr/bin/java", "root",
                "users", "host1");

        zms.putServiceIdentity(mockDomRsrcCtx, "ServiceRetrieveDom1", "Service1", auditRef, service);

        try {
            zms.getServiceIdentity(mockDomRsrcCtx, "ServiceRetrieveDom1", "Service");
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 404);
        }
        try {
            zms.getServiceIdentity(mockDomRsrcCtx, "ServiceRetrieveDom1", "Service2");
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 404);
        }
        try {
            zms.getServiceIdentity(mockDomRsrcCtx, "ServiceRetrieveDom1", "Service11");
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 404);
        }

        zms.deleteTopLevelDomain(mockDomRsrcCtx, "ServiceRetrieveDom1", auditRef);
    }
    
    @Test
    public void testRetrieveServiceIdentityValid() {
        
        String domainName = "serviceretrievedom2";
        String serviceName = "service1";
        
        TopLevelDomain dom1 = createTopLevelDomainObject(domainName,
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        ServiceIdentity service = createServiceObject(domainName,
                serviceName, "http://localhost", "/usr/bin/java", "root",
                "users", "host1");

        zms.putServiceIdentity(mockDomRsrcCtx, domainName, "Service1", auditRef, service);

        ServiceIdentity serviceRes = zms.getServiceIdentity(mockDomRsrcCtx, domainName, serviceName);
        assertNotNull(serviceRes);
        assertEquals(serviceRes.getName(), domainName + "." + serviceName);
        assertEquals(serviceRes.getExecutable(), "/usr/bin/java");
        assertEquals(serviceRes.getGroup(), "users");
        assertEquals(serviceRes.getUser(), "root");

        // provider endpoint is a system meta attribute so we shouldn't saved it
        assertNull(serviceRes.getProviderEndpoint());

        List<String> hosts = serviceRes.getHosts();
        assertNotNull(hosts);
        assertEquals(hosts.size(), 1);
        assertEquals(hosts.get(0), "host1");

        zms.deleteTopLevelDomain(mockDomRsrcCtx, domainName, auditRef);
    }
    
    @Test
    public void testGetProviderRoleActionPolicyNotFound() {
        
        String domainName = "coretech";
        
        TopLevelDomain dom1 = createTopLevelDomainObject(domainName,
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        Assertion assertion = new Assertion();
        assertion.setAction("read");
        assertion.setEffect(AssertionEffect.ALLOW);
        assertion.setResource("coretech:*");
        assertion.setRole("coretech:role.role1");

        Policy policy = new Policy().setName("coretech:policy.provider");
        policy.setAssertions(new ArrayList<>());
        policy.getAssertions().add(assertion);
        
        zms.putPolicy(mockDomRsrcCtx, domainName, "provider", auditRef, policy);

        assertEquals(zms.getProviderRoleAction(domainName, "policy1"), "");
        zms.deleteTopLevelDomain(mockDomRsrcCtx, domainName, auditRef);
    }
    
    @Test
    public void testGetProviderRoleActionAssertionNoMatch() {
        
        String domainName = "coretech";
        
        TopLevelDomain dom1 = createTopLevelDomainObject(domainName,
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        Assertion assertion = new Assertion();
        assertion.setAction("read");
        assertion.setEffect(AssertionEffect.ALLOW);
        assertion.setResource("coretech:*");
        assertion.setRole("coretech:role.role1");

        Policy policy = new Policy().setName("coretech:policy.provider");
        policy.setAssertions(new ArrayList<>());
        policy.getAssertions().add(assertion);
        
        zms.putPolicy(mockDomRsrcCtx, domainName, "provider", auditRef, policy);

        assertEquals(zms.getProviderRoleAction(domainName, "provider"), "");
        zms.deleteTopLevelDomain(mockDomRsrcCtx, domainName, auditRef);
    }

    @Test
    public void testGetProviderRoleActionNoAssertions() {

        String domainName = "coretech";

        TopLevelDomain dom1 = createTopLevelDomainObject(domainName,
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        Policy policy = new Policy().setName("coretech:policy.provider");
        policy.setAssertions(new ArrayList<>());

        zms.putPolicy(mockDomRsrcCtx, domainName, "provider", auditRef, policy);

        assertEquals(zms.getProviderRoleAction(domainName, "provider"), "");
        zms.deleteTopLevelDomain(mockDomRsrcCtx, domainName, auditRef);
    }

    @Test
    public void testGetProviderRoleActionAssertionActionNull() {
        
        String domainName = "coretech";
        
        TopLevelDomain dom1 = createTopLevelDomainObject(domainName,
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);
        
        Assertion assertion = new Assertion();
        assertion.setEffect(AssertionEffect.ALLOW);
        assertion.setResource("coretech:*");
        assertion.setRole("coretech:role.provider");
        
        Policy policy = new Policy().setName("coretech:policy.provider");
        policy.setAssertions(new ArrayList<>());
        policy.getAssertions().add(assertion);
        
        try {
            zms.putPolicy(mockDomRsrcCtx, domainName, "provider", auditRef, policy);
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 400);
        }
        
        assertEquals(zms.getProviderRoleAction(domainName, "provider"), "");
        zms.deleteTopLevelDomain(mockDomRsrcCtx, domainName, auditRef);
    }
    
    @Test
    public void testGetProviderRoleActionValid() {
        
        String domainName = "coretech";
        
        TopLevelDomain dom1 = createTopLevelDomainObject(domainName,
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);
        
        Assertion assertion = new Assertion();
        assertion.setAction("read");
        assertion.setEffect(AssertionEffect.ALLOW);
        assertion.setResource("coretech:*");
        assertion.setRole("coretech:role.provider");
        
        Policy policy = new Policy().setName("coretech:policy.provider");
        policy.setAssertions(new ArrayList<>());
        policy.getAssertions().add(assertion);
        
        zms.putPolicy(mockDomRsrcCtx, domainName, "provider", auditRef, policy);
        
        assertEquals(zms.getProviderRoleAction(domainName, "provider"), "read");
        zms.deleteTopLevelDomain(mockDomRsrcCtx, domainName, auditRef);
    }
    
    @Test
    public void testListDomains() {

        TopLevelDomain dom1 = createTopLevelDomainObject("ListDom1",
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        TopLevelDomain dom2 = createTopLevelDomainObject("ListDom2",
                "Test Domain2", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom2);

        DomainList domList = zms.listDomains(null, null, null, null, 0, false);
        assertNotNull(domList);

        assertTrue(domList.getNames().contains("ListDom1".toLowerCase()));
        assertTrue(domList.getNames().contains("ListDom2".toLowerCase()));

        zms.deleteTopLevelDomain(mockDomRsrcCtx, "ListDom1", auditRef);
        zms.deleteTopLevelDomain(mockDomRsrcCtx, "ListDom2", auditRef);
    }

    @Test
    public void testListDomainsParamsLimit() {

        TopLevelDomain dom1 = createTopLevelDomainObject("LimitDom1",
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        TopLevelDomain dom2 = createTopLevelDomainObject("LimitDom2",
                "Test Domain2", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom2);

        DomainList domList = zms.listDomains(1, null, null, null, 0, false);
        assertEquals(1, domList.getNames().size());

        zms.deleteTopLevelDomain(mockDomRsrcCtx, "LimitDom1", auditRef);
        zms.deleteTopLevelDomain(mockDomRsrcCtx, "LimitDom2", auditRef);
    }

    @Test
    public void testListDomainsParamsSkip() {

        TopLevelDomain dom1 = createTopLevelDomainObject("SkipDom1",
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        TopLevelDomain dom2 = createTopLevelDomainObject("SkipDom2",
                "Test Domain2", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom2);

        TopLevelDomain dom3 = createTopLevelDomainObject("SkipDom3",
                "Test Domain3", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom3);

        DomainList domList = zms.listDomains(null, null, null, null, 0, false);
        int size = domList.getNames().size();
        assertTrue(size > 3);

        // ask for only for 2 domains back
        domList = zms.listDomains(2, null, null, null, 0, false);
        assertEquals(domList.getNames().size(), 2);

        // ask for the remaining domains
        DomainList remList = zms.listDomains(null, domList.getNext(), null, null, 0, false);
        assertEquals(remList.getNames().size(), size - 2);

        zms.deleteTopLevelDomain(mockDomRsrcCtx, "SkipDom1", auditRef);
        zms.deleteTopLevelDomain(mockDomRsrcCtx, "SkipDom2", auditRef);
        zms.deleteTopLevelDomain(mockDomRsrcCtx, "SkipDom3", auditRef);
    }

    @Test
    public void testListDomainsParamsPrefix() {

        String noPrefixDom = "noprefixdom1";
        String prefixDom = "prefixdom2";
        
        TopLevelDomain dom1 = createTopLevelDomainObject(noPrefixDom,
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        TopLevelDomain dom2 = createTopLevelDomainObject(prefixDom,
                "Test Domain2", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom2);

        DomainList domList = zms.listDomains(null, null, "prefix", null, 0, false);

        assertFalse(domList.getNames().contains(noPrefixDom));
        assertTrue(domList.getNames().contains(prefixDom));

        zms.deleteTopLevelDomain(mockDomRsrcCtx, noPrefixDom, auditRef);
        zms.deleteTopLevelDomain(mockDomRsrcCtx, prefixDom, auditRef);
    }

    @Test
    public void testListDomainsParamsDepth() {

        TopLevelDomain dom1 = createTopLevelDomainObject("DepthDom1",
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        SubDomain dom2 = createSubDomainObject("DepthDom2", "DepthDom1",
                "Test Domain2", "testOrg", adminUser);
        zms.postSubDomain(mockDomRsrcCtx, "DepthDom1", auditRef, dom2);

        SubDomain dom3 = createSubDomainObject("DepthDom3",
                "DepthDom1.DepthDom2", "Test Domain3", "testOrg", adminUser);
        zms.postSubDomain(mockDomRsrcCtx, "DepthDom1.DepthDom2", auditRef, dom3);

        DomainList domList = zms.listDomains(null, null, null, 1, 0, false);

        assertTrue(domList.getNames().contains("DepthDom1".toLowerCase()));
        assertTrue(domList.getNames().contains("DepthDom1.DepthDom2".toLowerCase()));
        assertFalse(domList.getNames().contains("DepthDom1.DepthDom2.DepthDom3".toLowerCase()));

        zms.deleteSubDomain(mockDomRsrcCtx, "DepthDom1.DepthDom2", "DepthDom3", auditRef);
        zms.deleteSubDomain(mockDomRsrcCtx, "DepthDom1", "DepthDom2", auditRef);
        zms.deleteTopLevelDomain(mockDomRsrcCtx, "DepthDom1", auditRef);
    }
    
    @Test
    public void testListModifiedDomains() {

        TopLevelDomain dom1 = createTopLevelDomainObject("ListDomMod1",
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        TopLevelDomain dom2 = createTopLevelDomainObject("ListDomMod2",
                "Test Domain2", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom2);

        DomainMetaList domModList = zms.dbService.listModifiedDomains(0);
        assertNotNull(domModList);
        assertTrue(domModList.getDomains().size() > 1);

        boolean dom1Found = false;
        boolean dom2Found = false;
        for (Domain domName : domModList.getDomains()) {
            if (domName.getName().equalsIgnoreCase("ListDomMod1")) {
                dom1Found = true;
            } else if (domName.getName().equalsIgnoreCase("ListDomMod2")) {
                dom2Found = true;
            }
        }

        assertTrue(dom1Found);
        assertTrue(dom2Found);

        zms.deleteTopLevelDomain(mockDomRsrcCtx, "ListDomMod1", auditRef);
        zms.deleteTopLevelDomain(mockDomRsrcCtx, "ListDomMod2", auditRef);
    }

    @Test
    public void testListModifiedDomainsMillis() {

        long timestamp = System.currentTimeMillis() - 1001;

        TopLevelDomain dom1 = createTopLevelDomainObject("ListDomMod1",
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        TopLevelDomain dom2 = createTopLevelDomainObject("ListDomMod2",
                "Test Domain2", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom2);

        DomainMetaList domModList = zms.dbService.listModifiedDomains(timestamp);
        assertNotNull(domModList);
        assertTrue(domModList.getDomains().size() > 1);

        boolean dom1Found = false;
        boolean dom2Found = false;
        for (Domain domName : domModList.getDomains()) {
            if (domName.getName().equalsIgnoreCase("ListDomMod1")) {
                dom1Found = true;
            } else if (domName.getName().equalsIgnoreCase("ListDomMod2")) {
                dom2Found = true;
            }
        }

        assertTrue(dom1Found);
        assertTrue(dom2Found);

        timestamp += 10000; // add 10 seconds
        domModList = zms.dbService.listModifiedDomains(timestamp);
        assertNotNull(domModList);
        assertEquals(0, domModList.getDomains().size());

        zms.deleteTopLevelDomain(mockDomRsrcCtx, "ListDomMod1", auditRef);
        zms.deleteTopLevelDomain(mockDomRsrcCtx, "ListDomMod2", auditRef);
    }
    
    @Test
    public void testVirtualHomeDomain() {
        
        Authority principalAuthority = new com.yahoo.athenz.common.server.debug.DebugPrincipalAuthority();
        
        Principal principal = SimplePrincipal.create("user", "user1", "v=U1;d=user;n=user1;s=signature",
                0, principalAuthority);
        
        AthenzDomain virtualDomain = zms.virtualHomeDomain(principal, "user.user1");
        assertNotNull(virtualDomain);
        
        List<Role> roles = virtualDomain.getRoles();
        assertNotNull(roles);
        Role adminRole = null;
        for (Role role : roles) {
            if (role.getName().equals("user.user1:role.admin")) {
                adminRole = role;
                break;
            }
        }
        assertNotNull(adminRole);
        List<RoleMember> roleMembers = adminRole.getRoleMembers();
        assertEquals(roleMembers.size(), 1);
        assertEquals(roleMembers.get(0).getMemberName(), "user.user1");
        
        List<Policy> policies = virtualDomain.getPolicies();
        assertNotNull(policies);
        Policy adminPolicy = null;
        for (Policy policy : policies) {
            if (policy.getName().equals("user.user1:policy.admin")) {
                adminPolicy = policy;
                break;
            }
        }
        assertNotNull(adminPolicy);
    }

    @Test
    public void testVirtualHomeDomainDifferentUserHome() {
        
        Authority principalAuthority = new com.yahoo.athenz.common.server.debug.DebugPrincipalAuthority();
        
        Principal principal = SimplePrincipal.create("user", "john.smith", "v=U1;d=user;n=john.smith;s=signature",
                0, principalAuthority);
        
        AthenzDomain virtualDomain = zms.virtualHomeDomain(principal, "home.john-smith");
        assertNotNull(virtualDomain);
        
        List<Role> roles = virtualDomain.getRoles();
        assertNotNull(roles);
        Role adminRole = null;
        for (Role role : roles) {
            if (role.getName().equals("home.john-smith:role.admin")) {
                adminRole = role;
                break;
            }
        }
        assertNotNull(adminRole);
        List<RoleMember> roleMembers = adminRole.getRoleMembers();
        assertEquals(roleMembers.size(), 1);
        assertEquals(roleMembers.get(0).getMemberName(), "user.john.smith");
        
        List<Policy> policies = virtualDomain.getPolicies();
        assertNotNull(policies);
        Policy adminPolicy = null;
        for (Policy policy : policies) {
            if (policy.getName().equals("home.john-smith:policy.admin")) {
                adminPolicy = policy;
                break;
            }
        }
        assertNotNull(adminPolicy);
    }
    
    @Test
    public void testDeletePublicKeyEntry() {
        
        TopLevelDomain dom1 = createTopLevelDomainObject("ServiceDelPubKeyDom1",
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        ServiceIdentity service = createServiceObject("ServiceDelPubKeyDom1",
                "Service1", "http://localhost", "/usr/bin/java", "root",
                "users", "host1");

        zms.putServiceIdentity(mockDomRsrcCtx, "ServiceDelPubKeyDom1", "Service1", auditRef, service);

        zms.deletePublicKeyEntry(mockDomRsrcCtx, "ServiceDelPubKeyDom1", "Service1", "1", auditRef);
        ServiceIdentity serviceRes = zms.getServiceIdentity(mockDomRsrcCtx, "ServiceDelPubKeyDom1", "Service1");
        List<PublicKeyEntry> keyList = serviceRes.getPublicKeys();
        boolean found = false;
        for (PublicKeyEntry entry : keyList) {
            if (entry.getId().equals("1")) {
                found = true;
                break;
            }
        }
        assertFalse(found);
        
        zms.deleteTopLevelDomain(mockDomRsrcCtx, "ServiceDelPubKeyDom1", auditRef);
    }
 
    @Test
    public void testDeletePublicKeyEntryMissingAuditRef() {
        String domain = "testDeletePublicKeyEntryMissingAuditRef";
        TopLevelDomain dom = createTopLevelDomainObject(
                domain, "Test Domain1", "testOrg", adminUser);
        dom.setAuditEnabled(true);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom);

        ServiceIdentity service = createServiceObject(
            domain,
            "Service1", "http://localhost", "/usr/bin/java", "root",
            "users", "host1");
        zms.putServiceIdentity(mockDomRsrcCtx, domain, "Service1", auditRef, service);

        PublicKeyEntry keyEntry = new PublicKeyEntry();
        keyEntry.setId("zone1");
        keyEntry.setKey(pubKeyK2);
        zms.putPublicKeyEntry(mockDomRsrcCtx, domain, "Service1", "zone1", auditRef, keyEntry);
        try {
            zms.deletePublicKeyEntry(mockDomRsrcCtx, domain, "Service1", "1", null);
            fail("requesterror not thrown by deletePublicKeyEntry.");
        } catch (ResourceException ex) {
            assertEquals(400, ex.getCode());
            assertTrue(ex.getMessage().contains("Audit reference required"));
        } finally {
            zms.deleteTopLevelDomain(mockDomRsrcCtx, domain, auditRef);
        }
    }

    @Test
    public void testDeletePublicKeyEntryDomainNotFound() {
        
        TopLevelDomain dom1 = createTopLevelDomainObject("ServiceDelPubKeyDom2",
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        ServiceIdentity service = createServiceObject("ServiceDelPubKeyDom2",
                "Service1", "http://localhost", "/usr/bin/java", "root",
                "users", "host1");

        zms.putServiceIdentity(mockDomRsrcCtx, "ServiceDelPubKeyDom2", "Service1", auditRef, service);

        // this should throw a not found exception
        try {
            zms.deletePublicKeyEntry(mockDomRsrcCtx, "UnknownPublicKeyDomain", "Service1", "1", auditRef);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 404);
        }

        zms.deleteTopLevelDomain(mockDomRsrcCtx, "ServiceDelPubKeyDom2", auditRef);
    }
    
    @Test
    public void testDeletePublicKeyEntryInvalidService() {

        TestAuditLogger alogger = new TestAuditLogger();
        ZMSImpl zmsImpl = getZmsImpl(alogger);

        TopLevelDomain dom1 = createTopLevelDomainObject("ServiceDelPubKeyDom2InvalidService",
                "Test Domain1", "testOrg", adminUser);
        zmsImpl.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        ServiceIdentity service = createServiceObject("ServiceDelPubKeyDom2InvalidService",
                "Service1", "http://localhost", "/usr/bin/java", "root",
                "users", "host1");

        zmsImpl.putServiceIdentity(mockDomRsrcCtx, "ServiceDelPubKeyDom2InvalidService",
                "Service1", auditRef, service);

        // this should throw an invalid request exception
        try {
            zmsImpl.deletePublicKeyEntry(mockDomRsrcCtx, "ServiceDelPubKeyDom2InvalidService",
                    "Service1.Service2", "1", auditRef);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 400);
        }

        zmsImpl.deleteTopLevelDomain(mockDomRsrcCtx, "ServiceDelPubKeyDom2InvalidService", auditRef);
    }
    
    @Test
    public void testDeletePublicKeyEntryServiceNotFound() {
        
        TopLevelDomain dom1 = createTopLevelDomainObject("ServiceDelPubKeyDom3",
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        ServiceIdentity service = createServiceObject("ServiceDelPubKeyDom3",
                "Service1", "http://localhost", "/usr/bin/java", "root",
                "users", "host1");

        zms.putServiceIdentity(mockDomRsrcCtx, "ServiceDelPubKeyDom3", "Service1", auditRef, service);

        // this should throw a not found exception
        try {
            zms.deletePublicKeyEntry(mockDomRsrcCtx, "ServiceDelPubKeyDom3", "ServiceNotFound", "1", auditRef);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 404);
        }

        zms.deleteTopLevelDomain(mockDomRsrcCtx, "ServiceDelPubKeyDom3", auditRef);
    }
    
    @Test
    public void testDeletePublicKeyEntryIdNotFound() {
        
        TopLevelDomain dom1 = createTopLevelDomainObject("ServiceDelPubKeyDom4",
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        ServiceIdentity service = createServiceObject("ServiceDelPubKeyDom4",
                "Service1", "http://localhost", "/usr/bin/java", "root",
                "users", "host1");

        zms.putServiceIdentity(mockDomRsrcCtx, "ServiceDelPubKeyDom4", "Service1", auditRef, service);

        // process invalid keys
        
        try {
            zms.deletePublicKeyEntry(mockDomRsrcCtx, "ServiceDelPubKeyDom4", "Service1", "zone", auditRef);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 404);
        }
        
        // make sure both 1 and 2 keys are still valid
        
        ServiceIdentity serviceRes = zms.getServiceIdentity(mockDomRsrcCtx, "ServiceDelPubKeyDom4", "Service1");
        List<PublicKeyEntry> keyList = serviceRes.getPublicKeys();
        boolean foundKey1 = false;
        boolean foundKey2 = false;
        for (PublicKeyEntry entry : keyList) {
            if (entry.getId().equals("1")) {
                foundKey1 = true;
            } else if (entry.getId().equals("2")) {
                foundKey2 = true;
            }
        }
        assertTrue(foundKey1);
        assertTrue(foundKey2);
        
        zms.deleteTopLevelDomain(mockDomRsrcCtx, "ServiceDelPubKeyDom4", auditRef);
    }
    
    @Test
    public void testGetPublicKeyEntry() {
        
        TopLevelDomain dom1 = createTopLevelDomainObject("ServicePubKeyDom1",
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        ServiceIdentity service = createServiceObject("ServicePubKeyDom1",
                "Service1", "http://localhost", "/usr/bin/java", "root",
                "users", "host1");

        zms.putServiceIdentity(mockDomRsrcCtx, "ServicePubKeyDom1", "Service1", auditRef, service);

        PublicKeyEntry entry = zms.getPublicKeyEntry(mockDomRsrcCtx, "ServicePubKeyDom1", "Service1", "1");
        assertNotNull(entry);
        assertEquals(entry.getId(), "1");
        assertEquals(entry.getKey(), pubKeyK1);

        zms.deleteTopLevelDomain(mockDomRsrcCtx, "ServicePubKeyDom1", auditRef);
    }
    
    @Test
    public void testGetPublicKeyEntryInvalidService() {
        
        TopLevelDomain dom1 = createTopLevelDomainObject("ServicePubKeyDom2Invalid",
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        ServiceIdentity service = createServiceObject("ServicePubKeyDom2Invalid",
                "Service1", "http://localhost", "/usr/bin/java", "root",
                "users", "host1");

        zms.putServiceIdentity(mockDomRsrcCtx, "ServicePubKeyDom2Invalid", "Service1", auditRef, service);

        // this should throw an invalid request exception
        try {
            zms.getPublicKeyEntry(mockDomRsrcCtx, "ServicePubKeyDom2Invalid", "Service1.Service2", "1");
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 400);
        }

        zms.deleteTopLevelDomain(mockDomRsrcCtx, "ServicePubKeyDom2Invalid", auditRef);
    }
    
    @Test
    public void testGetPublicKeyEntryDomainNotFound() {
        
        TopLevelDomain dom1 = createTopLevelDomainObject("ServicePubKeyDom2",
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        ServiceIdentity service = createServiceObject("ServicePubKeyDom2",
                "Service1", "http://localhost", "/usr/bin/java", "root",
                "users", "host1");

        zms.putServiceIdentity(mockDomRsrcCtx, "ServicePubKeyDom2", "Service1", auditRef, service);

        // this should throw a not found exception
        try {
            zms.getPublicKeyEntry(mockDomRsrcCtx, "UnknownPublicKeyDomain", "Service1", "1");
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 404);
        }

        zms.deleteTopLevelDomain(mockDomRsrcCtx, "ServicePubKeyDom2", auditRef);
    }
    
    @Test
    public void testGetPublicKeyEntryServiceNotFound() {
        
        TopLevelDomain dom1 = createTopLevelDomainObject("ServicePubKeyDom3",
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        ServiceIdentity service = createServiceObject("ServicePubKeyDom3",
                "Service1", "http://localhost", "/usr/bin/java", "root",
                "users", "host1");

        zms.putServiceIdentity(mockDomRsrcCtx, "ServicePubKeyDom3", "Service1", auditRef, service);

        // this should throw a not found exception
        try {
            zms.getPublicKeyEntry(mockDomRsrcCtx, "ServicePubKeyDom3", "ServiceNotFound", "1");
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 404);
        }

        zms.deleteTopLevelDomain(mockDomRsrcCtx, "ServicePubKeyDom3", auditRef);
    }
    
    @Test
    public void testGetPublicKeyEntryIdNotFound() {
        
        TopLevelDomain dom1 = createTopLevelDomainObject("ServicePubKeyDom4",
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        ServiceIdentity service = createServiceObject("ServicePubKeyDom4",
                "Service1", "http://localhost", "/usr/bin/java", "root",
                "users", "host1");

        zms.putServiceIdentity(mockDomRsrcCtx, "ServicePubKeyDom4", "Service1", auditRef, service);

        // this should throw a not found exception
        try {
            zms.getPublicKeyEntry(mockDomRsrcCtx, "ServicePubKeyDom4", "Service1", "zone");
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 404);
        }

        zms.deleteTopLevelDomain(mockDomRsrcCtx, "ServicePubKeyDom4", auditRef);
    }
    
    @Test
    public void testPutPublicKeyEntryNew() {

        TopLevelDomain dom1 = createTopLevelDomainObject("ServicePutPubKeyDom1",
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        ServiceIdentity service = createServiceObject("ServicePutPubKeyDom1",
                "Service1", "http://localhost", "/usr/bin/java", "root",
                "users", "host1");

        zms.putServiceIdentity(mockDomRsrcCtx, "ServicePutPubKeyDom1", "Service1", auditRef, service);

        PublicKeyEntry keyEntry = new PublicKeyEntry();
        keyEntry.setId("zone1");
        keyEntry.setKey(pubKeyK2);

        zms.putPublicKeyEntry(mockDomRsrcCtx, "ServicePutPubKeyDom1", "Service1", "zone1", auditRef, keyEntry);

        ServiceIdentity serviceRes = zms.getServiceIdentity(mockDomRsrcCtx, "ServicePutPubKeyDom1", "Service1");
        List<PublicKeyEntry> keyList = serviceRes.getPublicKeys();
        boolean foundKey1 = false;
        boolean foundKey2 = false;
        boolean foundKeyZONE1 = false;
        for (PublicKeyEntry entry : keyList) {
            switch (entry.getId()) {
                case "1":
                    foundKey1 = true;
                    break;
                case "2":
                    foundKey2 = true;
                    break;
                case "zone1":
                    foundKeyZONE1 = true;
                    break;
            }
        }
        assertTrue(foundKey1);
        assertTrue(foundKey2);
        assertTrue(foundKeyZONE1);

        PublicKeyEntry entry = zms.getPublicKeyEntry(mockDomRsrcCtx, "ServicePutPubKeyDom1", "Service1", "zone1");
        assertNotNull(entry);
        assertEquals(entry.getId(), "zone1");
        assertEquals(entry.getKey(), pubKeyK2);

        zms.deleteTopLevelDomain(mockDomRsrcCtx, "ServicePutPubKeyDom1", auditRef);
    }

    @Test
    public void testPutPublicKeyEntryInvalidKey() {

        TopLevelDomain dom1 = createTopLevelDomainObject("ServicePutPubKeyDom1",
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        ServiceIdentity service = createServiceObject("ServicePutPubKeyDom1",
                "Service1", "http://localhost", "/usr/bin/java", "root",
                "users", "host1");

        zms.putServiceIdentity(mockDomRsrcCtx, "ServicePutPubKeyDom1", "Service1", auditRef, service);

        PublicKeyEntry keyEntry = new PublicKeyEntry();
        keyEntry.setId("zone1");
        keyEntry.setKey("some-invalid-key");

        try {
            zms.putPublicKeyEntry(mockDomRsrcCtx, "ServicePutPubKeyDom1", "Service1", "zone1", auditRef, keyEntry);
            fail();
        } catch (ResourceException ex) {
            assertTrue(ex.getMessage().contains("Invalid public key"));
        }

        zms.deleteTopLevelDomain(mockDomRsrcCtx, "ServicePutPubKeyDom1", auditRef);
    }

    @Test
    public void testPutPublicKeyEntryMissingAuditRef() {

        TestAuditLogger alogger = new TestAuditLogger();
        ZMSImpl zmsImpl = getZmsImpl(alogger);

        String domain = "testPutPublicKeyEntryMissingAuditRef";
        TopLevelDomain dom = createTopLevelDomainObject(
                domain, "Test Domain1", "testOrg", adminUser);
        dom.setAuditEnabled(true);
        zmsImpl.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom);

        ServiceIdentity service = createServiceObject(
            domain,
            "Service1", "http://localhost", "/usr/bin/java", "root",
            "users", "host1");
        zmsImpl.putServiceIdentity(mockDomRsrcCtx, domain, "Service1", auditRef, service);

        PublicKeyEntry keyEntry = new PublicKeyEntry();
        keyEntry.setId("zone1");
        keyEntry.setKey(pubKeyK2);
        
        try {
            zmsImpl.putPublicKeyEntry(mockDomRsrcCtx, domain, "Service1", "zone1", null, keyEntry);
            fail("requesterror not thrown by putPublicKeyEntry.");
        } catch (ResourceException ex) {
            assertEquals(400, ex.getCode());
            assertTrue(ex.getMessage().contains("Audit reference required"));
        } finally {
            zmsImpl.deleteTopLevelDomain(mockDomRsrcCtx, domain, auditRef);
        }
    }

    @Test
    public void testPutPublicKeyEntryInvalidService() {
        
        String domain = "testPutPublicKeyEntryInvalidService";
        TopLevelDomain dom = createTopLevelDomainObject(
                domain, "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom);

        ServiceIdentity service = createServiceObject(
            domain,
            "Service1", "http://localhost", "/usr/bin/java", "root",
            "users", "host1");
        zms.putServiceIdentity(mockDomRsrcCtx, domain, "Service1", auditRef, service);

        PublicKeyEntry keyEntry = new PublicKeyEntry();
        keyEntry.setId("zone1");
        keyEntry.setKey(pubKeyK2);
        
        try {
            zms.putPublicKeyEntry(mockDomRsrcCtx, domain, "Service1.Service2", "zone1", null, keyEntry);
            fail("requesterror not thrown by putPublicKeyEntry.");
        } catch (ResourceException ex) {
            assertEquals(400, ex.getCode());
        } finally {
            zms.deleteTopLevelDomain(mockDomRsrcCtx, domain, auditRef);
        }
    }
    
    @Test
    public void testPutPublicKeyEntryUpdate() {
        
        TopLevelDomain dom1 = createTopLevelDomainObject("ServicePutPubKeyDom1A",
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        ServiceIdentity service = createServiceObject("ServicePutPubKeyDom1A",
                "Service1", "http://localhost", "/usr/bin/java", "root",
                "users", "host1");

        zms.putServiceIdentity(mockDomRsrcCtx, "ServicePutPubKeyDom1A", "Service1", auditRef, service);

        PublicKeyEntry keyEntry = new PublicKeyEntry();
        keyEntry.setId("1");
        keyEntry.setKey(pubKeyK2);
        
        zms.putPublicKeyEntry(mockDomRsrcCtx, "ServicePutPubKeyDom1A", "Service1", "1", auditRef, keyEntry);
        
        ServiceIdentity serviceRes = zms.getServiceIdentity(mockDomRsrcCtx, "ServicePutPubKeyDom1A", "Service1");
        List<PublicKeyEntry> keyList = serviceRes.getPublicKeys();
        assertEquals(keyList.size(), 2);
        
        boolean foundKey1 = false;
        boolean foundKey2 = false;
        for (PublicKeyEntry entry : keyList) {
            if (entry.getId().equals("1")) {
                foundKey1 = true;
            } else if (entry.getId().equals("2")) {
                foundKey2 = true;
            }
        }
        
        assertTrue(foundKey1);
        assertTrue(foundKey2);
        
        PublicKeyEntry entry = zms.getPublicKeyEntry(mockDomRsrcCtx, "ServicePutPubKeyDom1A", "Service1", "1");
        assertNotNull(entry);
        assertEquals(entry.getId(), "1");
        assertEquals(entry.getKey(), pubKeyK2);
        
        zms.deleteTopLevelDomain(mockDomRsrcCtx, "ServicePutPubKeyDom1A", auditRef);
    }
    
    @Test
    public void testPutPublicKeyEntryDomainNotFound() {
        
        TopLevelDomain dom1 = createTopLevelDomainObject("ServicePutPubKeyDom2",
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        ServiceIdentity service = createServiceObject("ServicePutPubKeyDom2",
                "Service1", "http://localhost", "/usr/bin/java", "root",
                "users", "host1");

        zms.putServiceIdentity(mockDomRsrcCtx, "ServicePutPubKeyDom2", "Service1", auditRef, service);

        // this should throw a not found exception
        try {
            PublicKeyEntry keyEntry = new PublicKeyEntry();
            keyEntry.setId("zone1");
            keyEntry.setKey(pubKeyK2);
            
            zms.putPublicKeyEntry(mockDomRsrcCtx, "UnknownPublicKeyDomain", "Service1", "zone1", auditRef, keyEntry);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 404);
        }

        zms.deleteTopLevelDomain(mockDomRsrcCtx, "ServicePutPubKeyDom2", auditRef);
    }
    
    @Test
    public void testPutPublicKeyEntryServiceNotFound() {
        
        TopLevelDomain dom1 = createTopLevelDomainObject("ServicePutPubKeyDom3",
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        ServiceIdentity service = createServiceObject("ServicePutPubKeyDom3",
                "Service1", "http://localhost", "/usr/bin/java", "root",
                "users", "host1");

        zms.putServiceIdentity(mockDomRsrcCtx, "ServicePutPubKeyDom3", "Service1", auditRef, service);

        // this should throw a not found exception
        try {
            PublicKeyEntry keyEntry = new PublicKeyEntry();
            keyEntry.setId("zone1");
            keyEntry.setKey(pubKeyK2);
            
            zms.putPublicKeyEntry(mockDomRsrcCtx, "ServicePutPubKeyDom3", "ServiceNotFound", "zone1", auditRef, keyEntry);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 404);
        }

        zms.deleteTopLevelDomain(mockDomRsrcCtx, "ServicePutPubKeyDom3", auditRef);
    }

    @Test
    public void testDeletePublicKeyEntryIdNoMatch() {
        
        TopLevelDomain dom1 = createTopLevelDomainObject("ServicePutPubKeyDom4",
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        ServiceIdentity service = createServiceObject("ServicePutPubKeyDom4",
                "Service1", "http://localhost", "/usr/bin/java", "root",
                "users", "host1");

        zms.putServiceIdentity(mockDomRsrcCtx, "ServicePutPubKeyDom4", "Service1", auditRef, service);

        // this should throw invalid request exception
        
        try {
            PublicKeyEntry keyEntry = new PublicKeyEntry();
            keyEntry.setId("zone1");
            keyEntry.setKey(pubKeyK2);
            
            zms.putPublicKeyEntry(mockDomRsrcCtx, "ServicePutPubKeyDom4", "Service1", "zone2", auditRef, keyEntry);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 400);
        }
        
        zms.deleteTopLevelDomain(mockDomRsrcCtx, "ServicePutPubKeyDom4", auditRef);
    }
    
    @Test
    public void testConvertToLowerCaseAssertion() {
        
        Assertion assertion = new Assertion();
        assertion.setAction("Read");
        assertion.setEffect(AssertionEffect.ALLOW);
        assertion.setResource("coreTech:VIP.*");
        assertion.setRole("coretech:role.Role1");
        
        AthenzObject.ASSERTION.convertToLowerCase(assertion);
        assertEquals(assertion.getRole(), "coretech:role.role1");
        assertEquals(assertion.getAction(), "read");
        assertEquals(assertion.getResource(), "coretech:vip.*");

        // Check with case-sensitive flag
        new Assertion();
        assertion.setAction("Read");
        assertion.setEffect(AssertionEffect.ALLOW);
        assertion.setResource("coreTech:VIP.*");
        assertion.setRole("coretech:role.Role1");
        assertion.setCaseSensitive(true);

        AthenzObject.ASSERTION.convertToLowerCase(assertion);
        assertEquals(assertion.getRole(), "coretech:role.role1");
        assertEquals(assertion.getAction(), "Read");
        assertEquals(assertion.getResource(), "coretech:VIP.*");
    }
        
    @Test
    public void testRemoveQuotes() {
        
        assertEquals(zms.removeQuotes("abc"), "abc");
        assertEquals(zms.removeQuotes("\"abc"), "abc");
        assertEquals(zms.removeQuotes("abc\""), "abc");
        assertEquals(zms.removeQuotes("\"abc\""), "abc");
        assertEquals(zms.removeQuotes("\"a\"bc\""), "a\"bc");
    }
    
    @Test
    public void testConvertToLowerCaseList() {
        
        AthenzObject.LIST.convertToLowerCase(null);
        
        List<String> list = new ArrayList<>();
        list.add("item1");
        list.add("Item2");
        list.add("ITEM3");
        
        AthenzObject.LIST.convertToLowerCase(list);
        assertTrue(list.contains("item1"));
        assertTrue(list.contains("item2"));
        assertTrue(list.contains("item3"));
        assertEquals(list.size(), 3);
    }
    
    @Test
    public void testConvertToLowerCaseSubdomain() {
        
        SubDomain dom = createSubDomainObject("DepthDom2", "DepthDom1",
                "Test Domain2", "testOrg", "user.user3A");
        dom.setSignAlgorithm("RSA");
        AthenzObject.SUB_DOMAIN.convertToLowerCase(dom);
        assertEquals(dom.getName(), "depthdom2");
        assertEquals(dom.getParent(), "depthdom1");
        assertEquals(dom.getSignAlgorithm(), "rsa");
        assertTrue(dom.getAdminUsers().contains("user.user3a"));

        SubDomain dom2 = createSubDomainObject("DepthDom2", "DepthDom1",
                "Test Domain2", "testOrg", "user.user3B");
        DomainTemplateList templates = new DomainTemplateList();
        List<String> list = new ArrayList<>();
        list.add("platforms");
        list.add("vipNg");
        list.add("ATHENZ");
        templates.setTemplateNames(list);
        dom2.setTemplates(templates);
        AthenzObject.SUB_DOMAIN.convertToLowerCase(dom2);
        assertEquals(dom2.getName(), "depthdom2");
        assertEquals(dom2.getParent(), "depthdom1");
        assertTrue(dom2.getAdminUsers().contains("user.user3b"));
        templates = dom2.getTemplates();
        list = templates.getTemplateNames();
        assertEquals(3, list.size());
        assertTrue(list.contains("platforms"));
        assertTrue(list.contains("vipng"));
        assertTrue(list.contains("athenz"));
    }
    
    @Test
    public void testConvertToLowerCaseTopLeveldomain() {
        
        TopLevelDomain dom = createTopLevelDomainObject("TopLevelDomain",
                "Test Domain1", "testOrg", "user.USER3A");
        dom.setSignAlgorithm("EC");
        AthenzObject.TOP_LEVEL_DOMAIN.convertToLowerCase(dom);
        assertEquals(dom.getName(), "topleveldomain");
        assertEquals(dom.getSignAlgorithm(), "ec");
        assertTrue(dom.getAdminUsers().contains("user.user3a"));

        TopLevelDomain dom2 = createTopLevelDomainObject("TopLevelDomain",
                "Test Domain1", "testOrg", "user.USER3B");
        DomainTemplateList templates = new DomainTemplateList();
        List<String> list = new ArrayList<>();
        list.add("platforms");
        list.add("vipNg");
        list.add("ATHENZ");
        templates.setTemplateNames(list);
        dom2.setTemplates(templates);
        AthenzObject.TOP_LEVEL_DOMAIN.convertToLowerCase(dom2);
        assertEquals(dom2.getName(), "topleveldomain");
        assertTrue(dom2.getAdminUsers().contains("user.user3b"));
        templates = dom2.getTemplates();
        list = templates.getTemplateNames();
        assertEquals(3, list.size());
        assertTrue(list.contains("platforms"));
        assertTrue(list.contains("vipng"));
        assertTrue(list.contains("athenz"));
    }
    
    @Test
    public void testConvertToLowerCaseUserdomain() {
        
        UserDomain dom = createUserDomainObject("USER3A",
                "Test Domain1", "testOrg");
        dom.setSignAlgorithm("RSA");
        AthenzObject.USER_DOMAIN.convertToLowerCase(dom);
        assertEquals(dom.getName(), "user3a");
        assertEquals(dom.getSignAlgorithm(), "rsa");

        UserDomain dom2 = createUserDomainObject("USER3B",
                "Test Domain1", "testOrg");
        DomainTemplateList templates = new DomainTemplateList();
        List<String> list = new ArrayList<>();
        list.add("platforms");
        list.add("vipNg");
        list.add("ATHENZ");
        templates.setTemplateNames(list);
        dom2.setTemplates(templates);

        AthenzObject.USER_DOMAIN.convertToLowerCase(dom2);
        assertEquals(dom2.getName(), "user3b");
        templates = dom2.getTemplates();
        list = templates.getTemplateNames();
        assertEquals(3, list.size());
        assertTrue(list.contains("platforms"));
        assertTrue(list.contains("vipng"));
        assertTrue(list.contains("athenz"));
    }
    
    @Test
    public void testConvertToLowerCasePublicKeyEntry() {
        PublicKeyEntry keyEntry = new PublicKeyEntry().setKey("KEY").setId("ZONE1");
        AthenzObject.PUBLIC_KEY_ENTRY.convertToLowerCase(keyEntry);
        assertEquals(keyEntry.getKey(), "KEY");
        assertEquals(keyEntry.getId(), "zone1");
    }
    
    @Test
    public void testConvertToLowerCaseQuota() {
        Quota quota = new Quota().setName("UpperCaseDomain");
        AthenzObject.QUOTA.convertToLowerCase(quota);
        assertEquals(quota.getName(), "uppercasedomain");
    }
    
    @Test
    public void testConvertToLowerCaseEntity() {
        Entity entity = createEntityObject("ABcEntity");
        AthenzObject.ENTITY.convertToLowerCase(entity);
        assertEquals(entity.getName(), "abcentity");
    }
    
    @Test
    public void testConvertToLowerCaseTemplate() {
        DomainTemplate template = new DomainTemplate();
        List<String> names = new ArrayList<>();
        names.add("Burbank");
        names.add("santa_Monica");
        names.add("playa");
        template.setTemplateNames(names);
        List<TemplateParam> params = new ArrayList<>();
        params.add(new TemplateParam().setName("Name1").setValue("value1"));
        params.add(new TemplateParam().setName("name2").setValue("Value2"));
        template.setParams(params);

        AthenzObject.DOMAIN_TEMPLATE.convertToLowerCase(template);
        assertEquals(template.getTemplateNames().size(), 3);
        assertTrue(template.getTemplateNames().contains("burbank"));
        assertTrue(template.getTemplateNames().contains("playa"));
        assertTrue(template.getTemplateNames().contains("santa_monica"));
        assertEquals(template.getParams().size(), 2);
        boolean param1Check = false;
        boolean param2Check = false;
        TemplateParam param1 = new TemplateParam().setName("name1").setValue("value1");
        TemplateParam param2 = new TemplateParam().setName("name2").setValue("value2");
        for (TemplateParam param : template.getParams()) {
            if (param.equals(param1)) {
                param1Check = true;
            } else if (param.equals(param2)) {
                param2Check = true;
            }
        }
        assertTrue(param1Check);
        assertTrue(param2Check);

        // passing null should be no-op

        AthenzObject.DOMAIN_TEMPLATE.convertToLowerCase(null);
    }
    
    @Test
    public void testConvertToLowerCaseTenancy() {
        Tenancy tenancy = createTenantObject("CoretecH", "STorage");
        List<String> groups = new ArrayList<>();
        groups.add("Burbank");
        groups.add("santa_monica");
        tenancy.setResourceGroups(groups);
        AthenzObject.TENANCY.convertToLowerCase(tenancy);
        assertEquals(tenancy.getDomain(), "coretech");
        assertEquals(tenancy.getService(), "storage");
        assertTrue(tenancy.getResourceGroups().contains("burbank"));
        assertTrue(tenancy.getResourceGroups().contains("santa_monica"));
    }
    
    @Test
    public void testConvertToLowerCaseDefaultAdmins() {

        List<String> adminList = new ArrayList<>();
        adminList.add("user.User1");
        adminList.add("user.user2");
        DefaultAdmins admins = new DefaultAdmins();
        admins.setAdmins(adminList);

        AthenzObject.DEFAULT_ADMINS.convertToLowerCase(admins);
        assertTrue(admins.getAdmins().contains("user.user1"));
        assertTrue(admins.getAdmins().contains("user.user2"));
    }
    
    @Test
    public void testConvertToLowerCaseTenantResourceGroupRolesNoActions() {

        TenantResourceGroupRoles tenantRoles = new TenantResourceGroupRoles()
                .setDomain("coreTech").setService("storaGe")
                .setTenant("DelTenantRolesDom1").setResourceGroup("Hockey");
        AthenzObject.TENANT_RESOURCE_GROUP_ROLES.convertToLowerCase(tenantRoles);
        assertEquals(tenantRoles.getDomain(), "coretech");
        assertEquals(tenantRoles.getService(), "storage");
        assertEquals(tenantRoles.getTenant(), "deltenantrolesdom1");
        assertEquals(tenantRoles.getResourceGroup(), "hockey");
    }
    
    @Test
    public void testConvertToLowerCaseProviderResourceGroupRolesNoActions() {

        ProviderResourceGroupRoles tenantRoles = new ProviderResourceGroupRoles()
                .setDomain("coreTech").setService("storaGe")
                .setTenant("DelTenantRolesDom1").setResourceGroup("Hockey");
        AthenzObject.PROVIDER_RESOURCE_GROUP_ROLES.convertToLowerCase(tenantRoles);
        assertEquals(tenantRoles.getDomain(), "coretech");
        assertEquals(tenantRoles.getService(), "storage");
        assertEquals(tenantRoles.getTenant(), "deltenantrolesdom1");
        assertEquals(tenantRoles.getResourceGroup(), "hockey");
    }
    
    @Test
    public void testConvertToLowerCaseGroupRole() {
        Role role = createRoleObject("RoleDomain", "roleName", null, "user.USER1", "user.user2");
        AthenzObject.ROLE.convertToLowerCase(role);
        assertEquals(role.getName(), "roledomain:role.rolename");
        List<String> checkList = new ArrayList<>();
        checkList.add("user.user1");
        checkList.add("user.user2");
        checkRoleMember(checkList, role.getRoleMembers());
    }

    @Test
    public void testConvertToLowerCaseRoleMeta() {
        RoleMeta roleMeta = new RoleMeta();
        roleMeta.setNotifyRoles("role1,Role2,roLE3");
        roleMeta.setUserAuthorityFilter("attr1,ATTR2");
        roleMeta.setUserAuthorityExpiration("ElevatedClearance");
        roleMeta.setSignAlgorithm("EC");
        AthenzObject.ROLE_META.convertToLowerCase(roleMeta);
        assertEquals(roleMeta.getNotifyRoles(), "role1,role2,role3");
        assertEquals(roleMeta.getUserAuthorityFilter(), "attr1,ATTR2");
        assertEquals(roleMeta.getUserAuthorityExpiration(), "ElevatedClearance");
        assertEquals(roleMeta.getSignAlgorithm(), "ec");
    }

    @Test
    public void testConvertToLowerCaseTrustRole() {
        Role role = createRoleObject("RoleDomain", "roleName", "TRUSTDomain");
        AthenzObject.ROLE.convertToLowerCase(role);
        assertEquals(role.getName(), "roledomain:role.rolename");
        assertEquals(role.getTrust(), "trustdomain");
    }
    
    @Test
    public void testConvertToLowerCaseMembershipWithRole() {
        Membership membership = new Membership().setMemberName("user.member1").setRoleName("ROLE1");
        AthenzObject.MEMBERSHIP.convertToLowerCase(membership);
        assertEquals(membership.getMemberName(), "user.member1");
        assertEquals(membership.getRoleName(), "role1");
    }
    
    @Test
    public void testConvertToLowerCaseRole() {
        
        Role role = new Role().setName("Role1");
        
        List<String> list = new ArrayList<>();
        list.add("item1");
        list.add("Item2");
        list.add("ITEM3");
        role.setMembers(list);
        
        List<RoleMember> roleMembers = new ArrayList<>();
        roleMembers.add(new RoleMember().setMemberName("item1"));
        roleMembers.add(new RoleMember().setMemberName("Item2"));
        roleMembers.add(new RoleMember().setMemberName("ITEM3"));
        role.setRoleMembers(roleMembers);
        
        AthenzObject.ROLE.convertToLowerCase(role);
        
        assertEquals(role.getName(), "role1");
        list = role.getMembers();
        assertTrue(list.contains("item1"));
        assertTrue(list.contains("item2"));
        assertTrue(list.contains("item3"));
        assertEquals(list.size(), 3);
        
        roleMembers = role.getRoleMembers();
        assertEquals(roleMembers.size(), 3);
        boolean item1 = false;
        boolean item2 = false;
        boolean item3 = false;
        for (RoleMember member : roleMembers) {
            switch (member.getMemberName()) {
                case "item1":
                    item1 = true;
                    break;
                case "item2":
                    item2 = true;
                    break;
                case "item3":
                    item3 = true;
                    break;
            }
        }
        assertTrue(item1);
        assertTrue(item2);
        assertTrue(item3);
    }
    
    @Test
    public void testConvertToLowerCaseMembershipWithoutRole() {
        Membership membership = new Membership().setMemberName("user.member1");
        AthenzObject.MEMBERSHIP.convertToLowerCase(membership);
        assertEquals(membership.getMemberName(), "user.member1");
    }
    
    @Test
    public void testConvertToLowerCaseServciceWithKeys() {
        ServiceIdentity service = createServiceObject("CoreTECH", "STORage",
                "http://localhost:4080", "jetty", "user", "group", "HOST1");
        List<PublicKeyEntry> publicKeyList = new ArrayList<>();
        PublicKeyEntry publicKeyEntry1 = new PublicKeyEntry();
        publicKeyEntry1.setKey(pubKeyK1);
        publicKeyEntry1.setId("ZONE1");
        publicKeyList.add(publicKeyEntry1);
        PublicKeyEntry publicKeyEntry2 = new PublicKeyEntry();
        publicKeyEntry2.setKey(pubKeyK2);
        publicKeyEntry2.setId("2");
        publicKeyList.add(publicKeyEntry2);
        service.setPublicKeys(publicKeyList);
        AthenzObject.SERVICE_IDENTITY.convertToLowerCase(service);
        assertEquals(service.getName(), "coretech.storage");
        assertTrue(service.getHosts().contains("host1"));
        assertEquals(service.getPublicKeys().get(0).getId(), "zone1");
        assertEquals(service.getPublicKeys().get(1).getId(), "2");
    }
    
    @Test
    public void testConvertToLowerCaseTenantRoleAction() {
        
        TenantRoleAction roleAction = new TenantRoleAction().setRole("ReaDer").setAction("READ");
        
        AthenzObject.TENANT_ROLE_ACTION.convertToLowerCase(roleAction);
        assertEquals(roleAction.getAction(), "read");
        assertEquals(roleAction.getRole(), "reader");
    }
    
    @Test
    public void testConvertToLowerCasePolicyNoAssertion() {
        
        Policy policy = new Policy();
        policy.setName(ZMSUtils.policyResourceName("CoreTech", "policy"));
        
        AthenzObject.POLICY.convertToLowerCase(policy);
        assertEquals(policy.getName(), "coretech:policy.policy");
        
        policy.setName(ZMSUtils.policyResourceName("newtech", "Policy"));
        
        AthenzObject.POLICY.convertToLowerCase(policy);
        assertEquals(policy.getName(), "newtech:policy.policy");
    }
    
    @Test
    public void testConvertToLowerCasePolicyMultipleAssertion() {
        
        Policy policy = new Policy();
        policy.setName(ZMSUtils.policyResourceName("CoreTech", "policy"));
        
        Assertion assertion1 = new Assertion();
        assertion1.setAction("Read");
        assertion1.setEffect(AssertionEffect.ALLOW);
        assertion1.setResource("coreTech:VIP.*");
        assertion1.setRole("coretech:role.Role1");
        
        Assertion assertion2 = new Assertion();
        assertion2.setAction("UPDATE");
        assertion2.setEffect(AssertionEffect.ALLOW);
        assertion2.setResource("CoreTech:VIP.*");
        assertion2.setRole("coretech:role.RoleAB");
        
        List<Assertion> assertList = new ArrayList<>();
        assertList.add(assertion1);
        assertList.add(assertion2);
        
        policy.setAssertions(assertList);
        
        AthenzObject.POLICY.convertToLowerCase(policy);
        assertEquals(policy.getName(), "coretech:policy.policy");
        Assertion assertion = policy.getAssertions().get(0);
        assertEquals(assertion.getRole(), "coretech:role.role1");
        assertEquals(assertion.getAction(), "read");
        assertEquals(assertion.getResource(), "coretech:vip.*");
        
        assertion = policy.getAssertions().get(1);
        assertEquals(assertion.getRole(), "coretech:role.roleab");
        assertEquals(assertion.getAction(), "update");
        assertEquals(assertion.getResource(), "coretech:vip.*");

        // Now check case-sensitive
        policy = new Policy();
        policy.setName(ZMSUtils.policyResourceName("CoreTech", "policy"));
        policy.setCaseSensitive(true);
        assertion1 = new Assertion();
        assertion1.setAction("Read");
        assertion1.setEffect(AssertionEffect.ALLOW);
        assertion1.setResource("coreTech:VIP.*");
        assertion1.setRole("coretech:role.Role1");

        assertion2 = new Assertion();
        assertion2.setAction("UPDATE");
        assertion2.setEffect(AssertionEffect.ALLOW);
        assertion2.setResource("CoreTech:VIP.*");
        assertion2.setRole("coretech:role.RoleAB");

        assertList = new ArrayList<>();
        assertList.add(assertion1);
        assertList.add(assertion2);

        policy.setAssertions(assertList);

        AthenzObject.POLICY.convertToLowerCase(policy);
        assertEquals(policy.getName(), "coretech:policy.policy");
        assertion = policy.getAssertions().get(0);
        assertEquals(assertion.getRole(), "coretech:role.role1");
        assertEquals(assertion.getAction(), "Read");
        assertEquals(assertion.getResource(), "coretech:VIP.*");

        assertion = policy.getAssertions().get(1);
        assertEquals(assertion.getRole(), "coretech:role.roleab");
        assertEquals(assertion.getAction(), "UPDATE");
        assertEquals(assertion.getResource(), "coretech:VIP.*");
    }
    
    @Test
    public void testConvertToLowerCasePolicyOneAssertion() {
        
        Policy policy = createPolicyObject("CoreTech", "NewPolicy");
        AthenzObject.POLICY.convertToLowerCase(policy);
        assertEquals(policy.getName(), "coretech:policy.newpolicy");
        Assertion assertion = policy.getAssertions().get(0);
        assertEquals(assertion.getRole(), "coretech:role.role1");
    }
    
    @Test
    public void testConvertToLowerCaseDomainTemplateList() {
        DomainTemplateList templates = new DomainTemplateList();
        List<String> list = new ArrayList<>();
        list.add("platforms");
        list.add("vipNg");
        list.add("ATHENZ");
        templates.setTemplateNames(list);
        AthenzObject.DOMAIN_TEMPLATE_LIST.convertToLowerCase(templates);

        list = templates.getTemplateNames();
        assertEquals(3, list.size());
        assertTrue(list.contains("platforms"));
        assertTrue(list.contains("vipng"));
        assertTrue(list.contains("athenz"));
    }

    @Test
    public void testProviderServiceDomain() {
        assertEquals(zms.providerServiceDomain("coretech.storage"), "coretech");
        assertEquals(zms.providerServiceDomain("coretech.hosted.storage"), "coretech.hosted");
        assertNull(zms.providerServiceDomain("coretech"));
        assertNull(zms.providerServiceDomain(".coretech"));
        assertNull(zms.providerServiceDomain("coretech."));
    }
    
    @Test
    public void testProviderServiceName() {
        assertEquals(zms.providerServiceName("coretech.storage"), "storage");
        assertEquals(zms.providerServiceName("coretech.hosted.storage"), "storage");
        assertNull(zms.providerServiceName("coretech"));
        assertNull(zms.providerServiceName(".coretech"));
        assertNull(zms.providerServiceName("coretech."));
    }
    
    @Test
    public void testIsAuthorizedProviderServiceInvalidService() {
        
        // null authorized service argument
        
        assertFalse(zms.isAuthorizedProviderService(null, "coretech", "storage", mockDomRestRsrcCtx.principal()));
        
        // service does not match provider details
        
        assertFalse(zms.isAuthorizedProviderService("coretech.storage", "coretech", "storage2", mockDomRestRsrcCtx.principal()));
        assertFalse(zms.isAuthorizedProviderService("coretech.storage", "coretech2", "storage", mockDomRestRsrcCtx.principal()));
        
        // domain does not exist in zms
        
        assertFalse(zms.isAuthorizedProviderService("not_present_domain.storage", "not_present_domain",
                "storage", mockDomRestRsrcCtx.principal()));
    }
    
    @Test
    public void testIsAuthorizedProviderServiceAuthorized() {
        
        String tenantDomain = "AuthorizedProviderDom1";
        String providerDomain = "coretech";
        setupTenantDomainProviderService(tenantDomain, providerDomain, "storage",
                "http://localhost:8090/tableprovider");

        // tenant is setup so let's setup up policy to authorize access to tenants
        
        Role role = createRoleObject(providerDomain, "self_serve", null, providerDomain + ".storage", null);
        zms.putRole(mockDomRsrcCtx, providerDomain, "self_serve", auditRef, role);
        
        Policy policy = createPolicyObject(providerDomain, "self_serve",
                "self_serve", "update", providerDomain + ":tenant.*", AssertionEffect.ALLOW);
        zms.putPolicy(mockDomRsrcCtx, providerDomain, "self_serve", auditRef, policy);
        
        assertTrue(zms.isAuthorizedProviderService(providerDomain + ".storage", providerDomain,
                "storage", mockDomRestRsrcCtx.principal()));
        
        zms.deleteTopLevelDomain(mockDomRsrcCtx, tenantDomain, auditRef);
        zms.deleteTopLevelDomain(mockDomRsrcCtx, providerDomain, auditRef);
    }
    
    @Test
    public void testIsAuthorizedProviderServiceNotAuthorized() {
        
        String tenantDomain = "AuthorizedProviderDom2";
        String providerDomain = "coretech";
        setupTenantDomainProviderService(tenantDomain, providerDomain, "storage",
                "http://localhost:8090/tableprovider");

        // tenant is setup but no policy to authorize access to tenants
        
        assertFalse(zms.isAuthorizedProviderService(providerDomain + ".storage", providerDomain,
                "storage", mockDomRestRsrcCtx.principal()));
        
        zms.deleteTopLevelDomain(mockDomRsrcCtx, tenantDomain, auditRef);
        zms.deleteTopLevelDomain(mockDomRsrcCtx, providerDomain, auditRef);
    }
    
    @Test
    public void testVerifyAuthorizedServiceOperation() {
        
        // null authorized service means it's all good
        
        zms.verifyAuthorizedServiceOperation(null, "putrole");
        
        // our test resource json file includes two services:
        // coretech.storage - allowed for putrole and putpolicy
        // sports.hockey - not allowed for any ops
        
        zms.verifyAuthorizedServiceOperation("coretech.storage", "putrole");
        zms.verifyAuthorizedServiceOperation("coretech.storage", "putpolicy");
        try {
            zms.verifyAuthorizedServiceOperation("coretech.storage", "postdomain");
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 403);
        }

        try {
            zms.verifyAuthorizedServiceOperation("coretech.storage", "deleterole");
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 403);
        }
        
        try {
            zms.verifyAuthorizedServiceOperation("sports.hockey", "putrole");
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 403);
        }
        try {
            zms.verifyAuthorizedServiceOperation("sports.hockey", "putpolicy");
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 403);
        }
        try {
            zms.verifyAuthorizedServiceOperation("sports.hockey", "deleterole");
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 403);
        }
        try {
            zms.verifyAuthorizedServiceOperation("sports.hockey", "putserviceidentity");
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 403);
        }
        
        // ATHENZ-1528
        // Try passing along operationItem key + value to see if verification works
        
        // First, try with AllowAll operation
        zms.verifyAuthorizedServiceOperation("coretech.newsvc", "putrole"); // putrole has no restriction. This should pass.
        
        // Second, try with restricted operation. Currently, putmembership only allow single operation item.
        zms.verifyAuthorizedServiceOperation("coretech.newsvc", "putmembership", "role", "platforms_deployer");
        zms.verifyAuthorizedServiceOperation("coretech.newsvc", "putmembership", "role", "platforms_different_deployer");
        zms.verifyAuthorizedServiceOperation("coretech.newsvc", "putmembership", "not_role", "platforms_role_deployer");
        
        // Third, try with restriction operation, with not-specified operation item.

        try {
            zms.verifyAuthorizedServiceOperation("coretech.newsvc", "putmembership", "role", "platforms_deployer_new");
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 403);
        }
        
        try {
            zms.verifyAuthorizedServiceOperation("coretech.newsvc", "putmembership", "not_role", "platforms_deployer_new_new");
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 403);
        }
        
        try {
            zms.verifyAuthorizedServiceOperation("coretech.storage2", "postdomain");
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 403);
        }
        
        try {
            zms.verifyAuthorizedServiceOperation("media.storage", "deleterole");
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 403);
        }
    }

    @Test
    public void testVerifyAuthorizedServiceRolePrefixOperation() {

        // our test resource json includes the following
        // role-prefix use case
        //        "coretech.updater": {
        //            "allowedOperations": [
        //               {
        //                 "name":"putmembership",
        //                  "items": {
        //                      "role-prefix" : [
        //                          "reader.org.",
        //                          "writer.domain."
        //                      ]
        //                  }
        //              }
        //            ]

        zms.verifyAuthorizedServiceRoleOperation(null, "putmembership", "role1");

        // Try passing along operationItem key + value to see if verification works

        zms.verifyAuthorizedServiceRoleOperation("coretech.updater", "putmembership", "reader.org.role1");
        zms.verifyAuthorizedServiceRoleOperation("coretech.updater", "putmembership", "writer.domain.role1");

        // try with restricted operation. Currently, putmembership only allow single operation item.
        zms.verifyAuthorizedServiceRoleOperation("coretech.newsvc", "putmembership", "platforms_deployer");
        zms.verifyAuthorizedServiceRoleOperation("coretech.newsvc", "putmembership", "platforms_different_deployer");

        // Third, try with restriction operation, with not-specified operation item.

        try {
            zms.verifyAuthorizedServiceRoleOperation("coretech.updater", "putmembership", "platforms_deployer_new");
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 403);
        }

        try {
            zms.verifyAuthorizedServiceRoleOperation("coretech.updater", "putmembership", "reader.org1.role1");
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 403);
        }

        try {
            zms.verifyAuthorizedServiceRoleOperation("coretech.newsvc", "putmembership", "platforms_deployer_new");
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 403);
        }
    }

    @Test
    public void testPutProviderResourceGroupRoles() {

        String tenantDomain = "putproviderresourcegrouproles";
        TopLevelDomain dom = createTopLevelDomainObject(tenantDomain, "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom);

        List<TenantRoleAction> roleActions = new ArrayList<>();
        for (Struct.Field f : RESOURCE_PROVIDER_ROLE_ACTIONS) {
            roleActions.add(new TenantRoleAction().setRole(f.name()).setAction(
                    (String) f.value()));
        }
        String providerService = "storage";
        String providerDomain = "coretech";
        String resourceGroup = "hockey";
        
        ProviderResourceGroupRoles providerRoles = new ProviderResourceGroupRoles()
                .setDomain(providerDomain).setService(providerService)
                .setTenant(tenantDomain).setRoles(roleActions)
                .setResourceGroup(resourceGroup);
        zms.putProviderResourceGroupRoles(mockDomRsrcCtx, tenantDomain, providerDomain, providerService,
                resourceGroup, auditRef, providerRoles);

        ProviderResourceGroupRoles tRoles = zms.getProviderResourceGroupRoles(mockDomRsrcCtx,
                tenantDomain, providerDomain, providerService, resourceGroup);
        
        assertNotNull(tRoles);
        assertEquals(providerDomain.toLowerCase(), tRoles.getDomain());
        assertEquals(providerService.toLowerCase(), tRoles.getService());
        assertEquals(tenantDomain.toLowerCase(), tRoles.getTenant());
        assertEquals(resourceGroup.toLowerCase(), tRoles.getResourceGroup());
        assertEquals(RESOURCE_PROVIDER_ROLE_ACTIONS.size(), tRoles.getRoles().size());

        // when we execute the same request, it should work without
        // rejecting the request
        
        zms.putProviderResourceGroupRoles(mockDomRsrcCtx, tenantDomain, providerDomain, providerService,
                resourceGroup, auditRef, providerRoles);

        tRoles = zms.getProviderResourceGroupRoles(mockDomRsrcCtx,
                tenantDomain, providerDomain, providerService, resourceGroup);
        
        assertNotNull(tRoles);
        assertEquals(providerDomain.toLowerCase(), tRoles.getDomain());
        assertEquals(providerService.toLowerCase(), tRoles.getService());
        assertEquals(tenantDomain.toLowerCase(), tRoles.getTenant());
        assertEquals(resourceGroup.toLowerCase(), tRoles.getResourceGroup());
        assertEquals(RESOURCE_PROVIDER_ROLE_ACTIONS.size(), tRoles.getRoles().size());
        
        zms.deleteTopLevelDomain(mockDomRsrcCtx, tenantDomain, auditRef);
    }
    
    @Test
    public void testPutProviderResourceGroupMultipleRoles() {

        String tenantDomain = "putproviderresourcegroupmultipleroles";
        TopLevelDomain dom = createTopLevelDomainObject(tenantDomain, "Test Domain1", "testOrg", "user.user1");
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom);

        List<TenantRoleAction> roleActions = new ArrayList<>();
        for (Struct.Field f : RESOURCE_PROVIDER_ROLE_ACTIONS) {
            roleActions.add(new TenantRoleAction().setRole(f.name()).setAction(
                    (String) f.value()));
        }
        String providerService = "storage";
        String providerDomain = "coretech";
        String resourceGroup1 = "hockey";
        String resourceGroup2 = "baseball";
        
        // add resource group1 roles
        
        ProviderResourceGroupRoles providerRoles = new ProviderResourceGroupRoles()
                .setDomain(providerDomain).setService(providerService)
                .setTenant(tenantDomain).setRoles(roleActions)
                .setResourceGroup(resourceGroup1);
        zms.putProviderResourceGroupRoles(mockDomRsrcCtx, tenantDomain, providerDomain, providerService,
                resourceGroup1, auditRef, providerRoles);

        // add resource group2 roles
        
        providerRoles = new ProviderResourceGroupRoles()
                .setDomain(providerDomain).setService(providerService)
                .setTenant(tenantDomain).setRoles(roleActions)
                .setResourceGroup(resourceGroup2);
        zms.putProviderResourceGroupRoles(mockDomRsrcCtx, tenantDomain, providerDomain, providerService,
                resourceGroup2, auditRef, providerRoles);

        // verify group 1 roles

        final String roleWriter = providerDomain + "." + providerService + ".res_group." + resourceGroup1 + ".writer";
        final String roleReader = providerDomain + "." + providerService + ".res_group." + resourceGroup1 + ".reader";

        ProviderResourceGroupRoles tRoles = zms.getProviderResourceGroupRoles(mockDomRsrcCtx,
                tenantDomain, providerDomain, providerService, resourceGroup1);
        
        assertNotNull(tRoles);
        assertEquals(providerDomain.toLowerCase(), tRoles.getDomain());
        assertEquals(providerService.toLowerCase(), tRoles.getService());
        assertEquals(tenantDomain.toLowerCase(), tRoles.getTenant());
        assertEquals(resourceGroup1.toLowerCase(), tRoles.getResourceGroup());
        assertEquals(RESOURCE_PROVIDER_ROLE_ACTIONS.size(), tRoles.getRoles().size());

        Role roleW = zms.getRole(mockDomRsrcCtx, tenantDomain, roleWriter, false, false, false);
        assertEquals(roleW.getRoleMembers().size(), 1);
        assertEquals(roleW.getRoleMembers().get(0).getMemberName(), mockDomRestRsrcCtx.principal().getFullName());

        Role roleR = zms.getRole(mockDomRsrcCtx, tenantDomain, roleReader, false, false, false);
        assertEquals(roleR.getRoleMembers().size(), 1);
        assertEquals(roleR.getRoleMembers().get(0).getMemberName(), mockDomRestRsrcCtx.principal().getFullName());

        // verify group 2 roles
        
        tRoles = zms.getProviderResourceGroupRoles(mockDomRsrcCtx,
                tenantDomain, providerDomain, providerService, resourceGroup2);
        
        assertNotNull(tRoles);
        assertEquals(providerDomain.toLowerCase(), tRoles.getDomain());
        assertEquals(providerService.toLowerCase(), tRoles.getService());
        assertEquals(tenantDomain.toLowerCase(), tRoles.getTenant());
        assertEquals(resourceGroup2.toLowerCase(), tRoles.getResourceGroup());
        assertEquals(RESOURCE_PROVIDER_ROLE_ACTIONS.size(), tRoles.getRoles().size());

        // add an additional service to the writer action role

        final String newMember = tenantDomain + ".backend";

        Membership mbr = generateMembership(roleWriter, newMember);
        zms.putMembership(mockDomRsrcCtx, tenantDomain, roleWriter, newMember, auditRef, mbr);

        // now let's re-add the group 1 roles again and verify that the
        // member list is correct

        providerRoles = new ProviderResourceGroupRoles()
                .setDomain(providerDomain).setService(providerService)
                .setTenant(tenantDomain).setRoles(roleActions)
                .setResourceGroup(resourceGroup1);
        zms.putProviderResourceGroupRoles(mockDomRsrcCtx, tenantDomain, providerDomain, providerService,
                resourceGroup1, auditRef, providerRoles);

        roleW = zms.getRole(mockDomRsrcCtx, tenantDomain, roleWriter, false, false, false);
        assertEquals(roleW.getRoleMembers().size(), 2);

        assertTrue(verifyRoleMember(roleW, mockDomRestRsrcCtx.principal().getFullName()));
        assertTrue(verifyRoleMember(roleW, newMember));

        roleR = zms.getRole(mockDomRsrcCtx, tenantDomain, roleReader, false, false, false);
        assertEquals(roleR.getRoleMembers().size(), 1);
        assertTrue(verifyRoleMember(roleW, mockDomRestRsrcCtx.principal().getFullName()));

        zms.deleteTopLevelDomain(mockDomRsrcCtx, tenantDomain, auditRef);
    }
    
    @Test
    public void testDeleteProviderResourceGroupRoles() {

        String tenantDomain = "deleteproviderresourcegrouproles";
        TopLevelDomain dom = createTopLevelDomainObject(tenantDomain, "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom);

        List<TenantRoleAction> roleActions = new ArrayList<>();
        for (Struct.Field f : RESOURCE_PROVIDER_ROLE_ACTIONS) {
            roleActions.add(new TenantRoleAction().setRole(f.name()).setAction(
                    (String) f.value()));
        }
        String providerService  = "storage";
        String providerDomain = "coretech";
        String resourceGroup = "hockey";
        
        ProviderResourceGroupRoles providerRoles = new ProviderResourceGroupRoles()
                .setDomain(providerDomain).setService(providerService)
                .setTenant(tenantDomain).setRoles(roleActions)
                .setResourceGroup(resourceGroup);
        zms.putProviderResourceGroupRoles(mockDomRsrcCtx, tenantDomain, providerDomain, providerService,
                resourceGroup, auditRef, providerRoles);

        ProviderResourceGroupRoles tRoles = zms.getProviderResourceGroupRoles(mockDomRsrcCtx,
                tenantDomain, providerDomain, providerService, resourceGroup);
        
        assertNotNull(tRoles);
        assertEquals(providerDomain.toLowerCase(), tRoles.getDomain());
        assertEquals(providerService.toLowerCase(), tRoles.getService());
        assertEquals(tenantDomain.toLowerCase(), tRoles.getTenant());
        assertEquals(resourceGroup.toLowerCase(), tRoles.getResourceGroup());
        assertEquals(RESOURCE_PROVIDER_ROLE_ACTIONS.size(), tRoles.getRoles().size());

        // now let's delete our resource group
        
        zms.deleteProviderResourceGroupRoles(mockDomRsrcCtx, tenantDomain, providerDomain, providerService,
                resourceGroup, auditRef);
        
        // now let's retrieve our resource group and verify we got 0 roles
        
        tRoles = zms.getProviderResourceGroupRoles(mockDomRsrcCtx,
                tenantDomain, providerDomain, providerService, resourceGroup);
        
        assertNotNull(tRoles);
        assertEquals(providerDomain.toLowerCase(), tRoles.getDomain());
        assertEquals(providerService.toLowerCase(), tRoles.getService());
        assertEquals(tenantDomain.toLowerCase(), tRoles.getTenant());
        assertEquals(resourceGroup.toLowerCase(), tRoles.getResourceGroup());
        assertEquals(0, tRoles.getRoles().size());
        
        zms.deleteTopLevelDomain(mockDomRsrcCtx, tenantDomain, auditRef);
    }
    
    @Test
    public void testDeleteProviderResourceGroupMultipleRoles() {

        String tenantDomain = "deleteproviderresourcegroupmultipleroles";
        TopLevelDomain dom = createTopLevelDomainObject(tenantDomain, "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom);

        List<TenantRoleAction> roleActions = new ArrayList<>();
        for (Struct.Field f : RESOURCE_PROVIDER_ROLE_ACTIONS) {
            roleActions.add(new TenantRoleAction().setRole(f.name()).setAction(
                    (String) f.value()));
        }
        String providerService  = "storage";
        String providerDomain = "coretech";
        String resourceGroup1 = "hockey";
        String resourceGroup2 = "baseball";
        
        // add resource group1 roles
        
        ProviderResourceGroupRoles providerRoles = new ProviderResourceGroupRoles()
                .setDomain(providerDomain).setService(providerService)
                .setTenant(tenantDomain).setRoles(roleActions)
                .setResourceGroup(resourceGroup1);
        zms.putProviderResourceGroupRoles(mockDomRsrcCtx, tenantDomain, providerDomain, providerService,
                resourceGroup1, auditRef, providerRoles);

        // add resource group2 roles
        
        providerRoles = new ProviderResourceGroupRoles()
                .setDomain(providerDomain).setService(providerService)
                .setTenant(tenantDomain).setRoles(roleActions)
                .setResourceGroup(resourceGroup2);
        zms.putProviderResourceGroupRoles(mockDomRsrcCtx, tenantDomain, providerDomain, providerService,
                resourceGroup2, auditRef, providerRoles);
        
        // now let's delete our resource group 1
        
        zms.deleteProviderResourceGroupRoles(mockDomRsrcCtx, tenantDomain, providerDomain, providerService,
                resourceGroup1, auditRef);
        
        // verify group 1 roles and it's size of 0
        
        ProviderResourceGroupRoles tRoles = zms.getProviderResourceGroupRoles(mockDomRsrcCtx,
                tenantDomain, providerDomain, providerService, resourceGroup1);
        
        assertNotNull(tRoles);
        assertEquals(providerDomain.toLowerCase(), tRoles.getDomain());
        assertEquals(providerService.toLowerCase(), tRoles.getService());
        assertEquals(tenantDomain.toLowerCase(), tRoles.getTenant());
        assertEquals(resourceGroup1.toLowerCase(), tRoles.getResourceGroup());
        assertEquals(0, tRoles.getRoles().size());
        
        // verify group 2 roles with valid size of roles
        
        tRoles = zms.getProviderResourceGroupRoles(mockDomRsrcCtx,
                tenantDomain, providerDomain, providerService, resourceGroup2);
        
        assertNotNull(tRoles);
        assertEquals(providerDomain.toLowerCase(), tRoles.getDomain());
        assertEquals(providerService.toLowerCase(), tRoles.getService());
        assertEquals(tenantDomain.toLowerCase(), tRoles.getTenant());
        assertEquals(resourceGroup2.toLowerCase(), tRoles.getResourceGroup());
        assertEquals(RESOURCE_PROVIDER_ROLE_ACTIONS.size(), tRoles.getRoles().size());
        
        // now let's delete our resource group 2
        
        zms.deleteProviderResourceGroupRoles(mockDomRsrcCtx, tenantDomain, providerDomain, providerService,
                resourceGroup2, auditRef);
        
        // now both get operations must return 0 for the size
        
        tRoles = zms.getProviderResourceGroupRoles(mockDomRsrcCtx,
                tenantDomain, providerDomain, providerService, resourceGroup1);
        
        assertNotNull(tRoles);
        assertEquals(0, tRoles.getRoles().size());
        
        tRoles = zms.getProviderResourceGroupRoles(mockDomRsrcCtx,
                tenantDomain, providerDomain, providerService, resourceGroup2);
        
        assertNotNull(tRoles);
        assertEquals(0, tRoles.getRoles().size());
        
        zms.deleteTopLevelDomain(mockDomRsrcCtx, tenantDomain, auditRef);
    }
    
    @Test
    public void testGetProviderResourceGroupRoles() {

        String tenantDomain = "getproviderresourcegrouproles";
        TopLevelDomain dom = createTopLevelDomainObject(tenantDomain, "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom);

        List<TenantRoleAction> roleActions = new ArrayList<>();
        for (Struct.Field f : RESOURCE_PROVIDER_ROLE_ACTIONS) {
            roleActions.add(new TenantRoleAction().setRole(f.name()).setAction(
                    (String) f.value()));
        }
        String providerService  = "storage";
        String providerDomain = "coretech";
        String resourceGroup = "hockey";
        
        ProviderResourceGroupRoles providerRoles = new ProviderResourceGroupRoles()
                .setDomain(providerDomain).setService(providerService)
                .setTenant(tenantDomain).setRoles(roleActions)
                .setResourceGroup(resourceGroup);
        zms.putProviderResourceGroupRoles(mockDomRsrcCtx, tenantDomain, providerDomain, providerService,
                resourceGroup, auditRef, providerRoles);

        ProviderResourceGroupRoles tRoles = zms.getProviderResourceGroupRoles(mockDomRsrcCtx,
                tenantDomain, providerDomain, providerService, resourceGroup);
        
        assertNotNull(tRoles);
        assertEquals(providerDomain.toLowerCase(), tRoles.getDomain());
        assertEquals(providerService.toLowerCase(), tRoles.getService());
        assertEquals(tenantDomain.toLowerCase(), tRoles.getTenant());
        assertEquals(resourceGroup.toLowerCase(), tRoles.getResourceGroup());
        assertEquals(RESOURCE_PROVIDER_ROLE_ACTIONS.size(), tRoles.getRoles().size());
        List<TenantRoleAction> traList = tRoles.getRoles();
        List<String> roles = new ArrayList<>();
        for (TenantRoleAction ra : traList) {
            roles.add(ra.getRole());
        }
        assertTrue(roles.contains("reader"));
        assertTrue(roles.contains("writer"));
        
        zms.deleteTopLevelDomain(mockDomRsrcCtx, tenantDomain, auditRef);
    }
    
    @Test
    public void testGetProviderResourceGroupRolesInvalid() {

        String tenantDomain = "getproviderresourcegrouprolesinvalid";
        TopLevelDomain dom = createTopLevelDomainObject(tenantDomain, "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom);

        // all invalid input with provider domain, resource and resource group
        // just returns an empty list for role actions.

        ProviderResourceGroupRoles tRoles = zms.getProviderResourceGroupRoles(mockDomRsrcCtx,
                tenantDomain, "test1", "invalid", "hockey");
        
        assertNotNull(tRoles);
        assertEquals(0, tRoles.getRoles().size());
        
        zms.deleteTopLevelDomain(mockDomRsrcCtx, tenantDomain, auditRef);
    }

    @Test
    public void testGetProviderResourceGroupRolesUnknownDomain() {

        // all invalid input with provider domain, resource and resource group
        // just returns an empty list for role actions but for invalid tenant
        // domain we get 404 exception

        try {
            zms.getProviderResourceGroupRoles(mockDomRsrcCtx, "unknown-domain",
                    "test1", "invalid", "hockey");
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 404);
        }
    }

    @Test
    public void testPutProviderResourceGroupRolesWithAuthorizedService() {

        String tenantDomain = "providerresourcegrouprolesauthorizedservice";
        String providerService  = "storage";
        String providerDomain = "coretech";
        String resourceGroup = "hockey";
        
        setupTenantDomainProviderService(tenantDomain, providerDomain, providerService,
                "http://localhost:8090/tableprovider");

        // tenant is setup so let's setup up policy to authorize access to tenants
        // without this role/policy we won't be authorized to add tenant roles
        // to the provider domain even with authorized service details
        
        Role role = createRoleObject(providerDomain, "self_serve", null,
                providerDomain + "." + providerService, null);
        zms.putRole(mockDomRsrcCtx, providerDomain, "self_serve", auditRef, role);
        
        Policy policy = createPolicyObject(providerDomain, "self_serve",
                "self_serve", "update", providerDomain + ":tenant.*", AssertionEffect.ALLOW);
        zms.putPolicy(mockDomRsrcCtx, providerDomain, "self_serve", auditRef, policy);
        
        // now we're going to setup our provider role call
        
        List<TenantRoleAction> roleActions = new ArrayList<>();
        for (Struct.Field f : RESOURCE_PROVIDER_ROLE_ACTIONS) {
            roleActions.add(new TenantRoleAction().setRole(f.name()).setAction(
                    (String) f.value()));
        }
        
        ProviderResourceGroupRoles providerRoles = new ProviderResourceGroupRoles()
                .setDomain(providerDomain).setService(providerService)
                .setTenant(tenantDomain).setRoles(roleActions)
                .setResourceGroup(resourceGroup);
        
        // we are going to create a principal object with authorized service
        // set to coretech.storage
        
        String userId = "user1";
        Authority principalAuthority = new com.yahoo.athenz.common.server.debug.DebugPrincipalAuthority();
        String unsignedCreds = "v=U1;d=user;n=" + userId;
        Principal principal = SimplePrincipal.create("user", userId, unsignedCreds + ";s=signature",
                0, principalAuthority);
        assertNotNull(principal);
        ((SimplePrincipal) principal).setUnsignedCreds(unsignedCreds);
        ((SimplePrincipal) principal).setUnsignedCreds(unsignedCreds);
        ((SimplePrincipal) principal).setAuthorizedService("coretech.storage");
        ResourceContext ctx = createResourceContext(principal, "putproviderresourcegrouproles");
        
        // after this call we should have roles set for both provider and tenant
        
        zms.putProviderResourceGroupRoles(ctx, tenantDomain, providerDomain, providerService,
                resourceGroup, auditRef, providerRoles);

        ProviderResourceGroupRoles pRoles = zms.getProviderResourceGroupRoles(ctx,
                tenantDomain, providerDomain, providerService, resourceGroup);
        
        assertNotNull(pRoles);
        assertEquals(providerDomain.toLowerCase(), pRoles.getDomain());
        assertEquals(providerService.toLowerCase(), pRoles.getService());
        assertEquals(tenantDomain.toLowerCase(), pRoles.getTenant());
        assertEquals(resourceGroup.toLowerCase(), pRoles.getResourceGroup());
        assertEquals(RESOURCE_PROVIDER_ROLE_ACTIONS.size(), pRoles.getRoles().size());
        List<TenantRoleAction> traList = pRoles.getRoles();
        List<String> roles = new ArrayList<>();
        for (TenantRoleAction ra : traList) {
            roles.add(ra.getRole());
        }
        assertTrue(roles.contains("reader"));
        assertTrue(roles.contains("writer"));
        
        // now get the tenant roles for the provider
        
        TenantResourceGroupRoles tRoles = zms.getTenantResourceGroupRoles(mockDomRsrcCtx, providerDomain,
                providerService, tenantDomain, resourceGroup);
        assertNotNull(tRoles);
        assertEquals(tRoles.getDomain(), providerDomain);
        assertEquals(tRoles.getService(), providerService);
        assertEquals(tRoles.getTenant(), tenantDomain);
        assertEquals(tRoles.getResourceGroup(), resourceGroup);
        assertEquals(RESOURCE_PROVIDER_ROLE_ACTIONS.size(), tRoles.getRoles().size());
        traList = pRoles.getRoles();
        roles = new ArrayList<>();
        for (TenantRoleAction ra : traList) {
            roles.add(ra.getRole());
        }
        assertTrue(roles.contains("reader"));
        assertTrue(roles.contains("writer"));
        
        // now we're going to delete the provider roles using the standard
        // resource object without the authorized service. in this case
        // the provider roles are going to be deleted but not the tenant
        // roles from the provider domain
        
        zms.deleteProviderResourceGroupRoles(mockDomRsrcCtx, tenantDomain, providerDomain,
                providerService, resourceGroup, auditRef);
        
        // so for tenant we're going to 0 provider roles
        
        pRoles = zms.getProviderResourceGroupRoles(mockDomRsrcCtx,
                tenantDomain, providerDomain, providerService, resourceGroup);
        
        assertNotNull(pRoles);
        assertEquals(0, pRoles.getRoles().size());
        
        // but for provider we're still going to get full set of roles
        
        tRoles = zms.getTenantResourceGroupRoles(mockDomRsrcCtx, providerDomain,
                providerService, tenantDomain, resourceGroup);
        assertNotNull(tRoles);
        assertEquals(2, tRoles.getRoles().size());
        
        // now this time we're going to delete with the principal with the
        // authorized service token
        
        zms.deleteProviderResourceGroupRoles(ctx, tenantDomain, providerDomain,
                providerService, resourceGroup, auditRef);
        
        // so for tenant we're still going to 0 provider roles
        
        pRoles = zms.getProviderResourceGroupRoles(ctx,
                tenantDomain, providerDomain, providerService, resourceGroup);
        
        assertNotNull(pRoles);
        assertEquals(0, pRoles.getRoles().size());
        
        // and for provider we're now going to get 0 tenant roles as well
        
        tRoles = zms.getTenantResourceGroupRoles(mockDomRsrcCtx, providerDomain,
                providerService, tenantDomain, resourceGroup);
        assertNotNull(tRoles);
        assertEquals(0, tRoles.getRoles().size());
        
        // clean up our domains
        
        zms.deleteTopLevelDomain(mockDomRsrcCtx, tenantDomain, auditRef);
        zms.deleteTopLevelDomain(mockDomRsrcCtx, providerDomain, auditRef);
    }
    
    @Test
    public void testProviderResourceGroupRolesWithAuthorizedServiceNoAccess() {

        TestAuditLogger alogger = new TestAuditLogger();
        ZMSImpl zmsImpl = getZmsImpl(alogger);

        String tenantDomain = "provrscgrprolesauthorizedservicenoaccess";
        String providerService  = "index";
        String providerDomain = "coretech";
        String resourceGroup = "hockey";
        
        setupTenantDomainProviderService(zmsImpl, tenantDomain, providerDomain, providerService,
                "http://localhost:8090/tableprovider");

        // tenant is setup so let's setup up policy to authorize access to tenants
        // without this role/policy we won't be authorized to add tenant roles
        // to the provider domain even with authorized service details
        
        Role role = createRoleObject(providerDomain, "self_serve", null,
                providerDomain + "." + providerService, null);
        zmsImpl.putRole(mockDomRsrcCtx, providerDomain, "self_serve", auditRef, role);
        
        Policy policy = createPolicyObject(providerDomain, "self_serve",
                "self_serve", "update", providerDomain + ":tenant.*", AssertionEffect.ALLOW);
        zmsImpl.putPolicy(mockDomRsrcCtx, providerDomain, "self_serve", auditRef, policy);
        
        // now we're going to setup our provider role call
        
        List<TenantRoleAction> roleActions = new ArrayList<>();
        for (Struct.Field f : RESOURCE_PROVIDER_ROLE_ACTIONS) {
            roleActions.add(new TenantRoleAction().setRole(f.name()).setAction(
                    (String) f.value()));
        }
        
        ProviderResourceGroupRoles providerRoles = new ProviderResourceGroupRoles()
                .setDomain(providerDomain).setService(providerService)
                .setTenant(tenantDomain).setRoles(roleActions)
                .setResourceGroup(resourceGroup);
        
        // we are going to create a principal object with authorized service
        // set to coretech.index
        
        String userId = "user1";
        Authority principalAuthority = new com.yahoo.athenz.common.server.debug.DebugPrincipalAuthority();
        String unsignedCreds = "v=U1;d=user;n=" + userId;
        Principal principal = SimplePrincipal.create("user", userId, unsignedCreds + ";s=signature",
                0, principalAuthority);
        assertNotNull(principal);
        ((SimplePrincipal) principal).setUnsignedCreds(unsignedCreds);
        ((SimplePrincipal) principal).setUnsignedCreds(unsignedCreds);
        ((SimplePrincipal) principal).setAuthorizedService("coretech.index");
        ResourceContext ctx = createResourceContext(principal);
        
        // this call should return an exception since we can't execute
        // the putproviderresourcegrouproles operation with our chained token
        
        try {
            zmsImpl.putProviderResourceGroupRoles(ctx, tenantDomain, providerDomain, providerService,
                    resourceGroup, auditRef, providerRoles);
            fail();
        } catch (ResourceException ex) {
            assertEquals(403, ex.getCode());
        }

        // clean up our domains
        
        zmsImpl.deleteTopLevelDomain(mockDomRsrcCtx, tenantDomain, auditRef);
        zmsImpl.deleteTopLevelDomain(mockDomRsrcCtx, providerDomain, auditRef);
    }

    @Test
    public void testPutProviderRolesWithResourceGroupEmptyRoleActions() {

        String domain = "testputproviderrolesnoroles";
        TopLevelDomain dom = createTopLevelDomainObject(
                domain, "Test Domain1", "testOrg", adminUser);
        dom.setAuditEnabled(true);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom);

        List<TenantRoleAction> roleActions = new ArrayList<>();
        String serviceName  = "storage";
        String tenantDomain = "tenantTestPutTenantRoles";
        String resourceGroup = "Group1";

        ProviderResourceGroupRoles providerRoles = new ProviderResourceGroupRoles().setDomain(domain)
                .setService(serviceName).setTenant(tenantDomain)
                .setRoles(roleActions).setResourceGroup(resourceGroup);

        try {
            zms.putProviderResourceGroupRoles(mockDomRsrcCtx, domain, serviceName, tenantDomain,
                    resourceGroup, auditRef, providerRoles);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 400);
        }

        zms.deleteTopLevelDomain(mockDomRsrcCtx, domain, auditRef);
    }

    @Test
    public void testOptionsUserTokenInvalidService() {
        
        // null service must return 400
        
        try {
            zms.optionsUserToken(mockDomRsrcCtx, "user1", null);
            fail();
        } catch (ResourceException ex) {
            assertEquals(400, ex.getCode());
        }

        // empty service must return 400
        
        try {
            zms.optionsUserToken(mockDomRsrcCtx, "user1", "");
            fail();
        } catch (ResourceException ex) {
            assertEquals(400, ex.getCode());
        }
        
        // unknown registered service must return 400
        try {
            zms.optionsUserToken(mockDomRsrcCtx, "user1", "unknown_service_name");
            fail();
        } catch (ResourceException ex) {
            assertEquals(400, ex.getCode());
        }

        // in a list all services must be valid - any invalid must return 400
        
        try {
            zms.optionsUserToken(mockDomRsrcCtx, "user1", "coretech.storage,unknown_service_name");
            fail();
        } catch (ResourceException ex) {
            assertEquals(400, ex.getCode());
        }
    }

    @Test
    public void testOptionsUserToken() {
        HttpServletRequest servletRequest = new MockHttpServletRequest();
        HttpServletResponse servletResponse = new MockHttpServletResponse();
        ResourceContext ctx = new RsrcCtxWrapper(servletRequest, servletResponse, null, false, null, new Object(), "apiName");

        zms.optionsUserToken(ctx, "user", "coretech.storage");
        assertEquals("GET", servletResponse.getHeader(ZMSConsts.HTTP_ACCESS_CONTROL_ALLOW_METHODS));
        assertEquals("2592000", servletResponse.getHeader(ZMSConsts.HTTP_ACCESS_CONTROL_MAX_AGE));
        assertEquals("true", servletResponse.getHeader(ZMSConsts.HTTP_ACCESS_CONTROL_ALLOW_CREDENTIALS));
        
        // using default values where we'll get back null for origin and no allow headers
        
        assertNull(servletResponse.getHeader(ZMSConsts.HTTP_ACCESS_CONTROL_ALLOW_ORIGIN));
        assertNull(servletResponse.getHeader(ZMSConsts.HTTP_ACCESS_CONTROL_ALLOW_HEADERS));
    }

    @Test
    public void testOptionsUserTokenRequestHeaders() {
        MockHttpServletRequest servletRequest = new MockHttpServletRequest();
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();
        ResourceContext ctx = new RsrcCtxWrapper(servletRequest, servletResponse, null, false, null, new Object(), "apiName");

        String origin = "https://zms.origin.athenzcompany.com";
        String requestHeaders = "X-Forwarded-For,Content-Type";
        servletRequest.addHeader(ZMSConsts.HTTP_ORIGIN, origin);
        servletRequest.addHeader(ZMSConsts.HTTP_ACCESS_CONTROL_REQUEST_HEADERS, requestHeaders);
        
        // this time we're going to try with multiple services
        
        zms.optionsUserToken(ctx, "user", "coretech.storage,coretech.index");
        assertEquals("GET", servletResponse.getHeader(ZMSConsts.HTTP_ACCESS_CONTROL_ALLOW_METHODS));
        assertEquals("2592000", servletResponse.getHeader(ZMSConsts.HTTP_ACCESS_CONTROL_MAX_AGE));
        assertEquals("true", servletResponse.getHeader(ZMSConsts.HTTP_ACCESS_CONTROL_ALLOW_CREDENTIALS));
        
        assertEquals(origin, servletResponse.getHeader(ZMSConsts.HTTP_ACCESS_CONTROL_ALLOW_ORIGIN));
        assertEquals(requestHeaders, servletResponse.getHeader(ZMSConsts.HTTP_ACCESS_CONTROL_ALLOW_HEADERS));
    }

    @Test
    public void testSetStandardCORSHeaders() {
        HttpServletRequest servletRequest = new MockHttpServletRequest();
        HttpServletResponse servletResponse = new MockHttpServletResponse();
        ResourceContext ctx = new RsrcCtxWrapper(servletRequest, servletResponse, null, false, null, new Object(), "apiName");

        zms.setStandardCORSHeaders(ctx);
        assertEquals("true", servletResponse.getHeader(ZMSConsts.HTTP_ACCESS_CONTROL_ALLOW_CREDENTIALS));
        
        // using default values where we'll get back null for origin and no allow headers
        
        assertNull(servletResponse.getHeader(ZMSConsts.HTTP_ACCESS_CONTROL_ALLOW_ORIGIN));
        assertNull(servletResponse.getHeader(ZMSConsts.HTTP_ACCESS_CONTROL_ALLOW_HEADERS));
    }

    @Test
    public void testSetStandardCORSHeadersRequestHeaders() {
        MockHttpServletRequest servletRequest = new MockHttpServletRequest();
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();
        ResourceContext ctx = new RsrcCtxWrapper(servletRequest, servletResponse, null, false, null, new Object(), "apiName");

        String origin = "https://zms.origin.athenzcompany.com";
        String requestHeaders = "X-Forwarded-For,Content-Type";
        servletRequest.addHeader(ZMSConsts.HTTP_ORIGIN, origin);
        servletRequest.addHeader(ZMSConsts.HTTP_ACCESS_CONTROL_REQUEST_HEADERS, requestHeaders);
        
        zms.setStandardCORSHeaders(ctx);
        assertEquals("true", servletResponse.getHeader(ZMSConsts.HTTP_ACCESS_CONTROL_ALLOW_CREDENTIALS));
        
        assertEquals(origin, servletResponse.getHeader(ZMSConsts.HTTP_ACCESS_CONTROL_ALLOW_ORIGIN));
        assertEquals(requestHeaders, servletResponse.getHeader(ZMSConsts.HTTP_ACCESS_CONTROL_ALLOW_HEADERS));
    }
    
    @Test
    public void testVerifyProviderEndpoint() {
        
        // http successful test cases (localhost or *.athenzcompany.com)
        assertTrue(zms.verifyProviderEndpoint("http://localhost"));
        assertTrue(zms.verifyProviderEndpoint("http://localhost:4080"));
        assertTrue(zms.verifyProviderEndpoint("http://localhost:4080/"));
        assertTrue(zms.verifyProviderEndpoint("http://localhost:4080/test1"));
        assertTrue(zms.verifyProviderEndpoint("http://host1.athenzcompany.com"));
        assertTrue(zms.verifyProviderEndpoint("http://host1.athenzcompany.com:4080"));
        assertTrue(zms.verifyProviderEndpoint("http://host1.athenzcompany.com:4080/"));
        assertTrue(zms.verifyProviderEndpoint("http://host1.athenzcompany.com:4080/test1"));
        
        // https successful test cases (localhost or *.athenzcompany.com)
        assertTrue(zms.verifyProviderEndpoint("https://localhost"));
        assertTrue(zms.verifyProviderEndpoint("https://localhost:4080"));
        assertTrue(zms.verifyProviderEndpoint("https://localhost:4080/"));
        assertTrue(zms.verifyProviderEndpoint("https://localhost:4080/test1"));
        assertTrue(zms.verifyProviderEndpoint("https://host1.athenzcompany.com"));
        assertTrue(zms.verifyProviderEndpoint("https://host1.athenzcompany.com:4080"));
        assertTrue(zms.verifyProviderEndpoint("https://host1.athenzcompany.com:4080/"));
        assertTrue(zms.verifyProviderEndpoint("https://host1.athenzcompany.com:4080/test1"));
        
        // class successful test case
        assertTrue(zms.verifyProviderEndpoint("class://com.yahoo.athenz.zms.ZMS"));
        
        // http invalid cases - not *.athenzcompany.com
        assertFalse(zms.verifyProviderEndpoint("http://host1.server.com"));
        assertFalse(zms.verifyProviderEndpoint("http://host1.server.com:4080"));
        assertFalse(zms.verifyProviderEndpoint("http://host1.server.com:4080/"));
        assertFalse(zms.verifyProviderEndpoint("http://host1.server.yahoo:4080/test1"));
        assertFalse(zms.verifyProviderEndpoint("http://host1.athenz.server.com:4080/test1"));
        assertFalse(zms.verifyProviderEndpoint("http://host1.athenz.ch:4080/test1"));
        
        // non-http scheme test cases
        assertFalse(zms.verifyProviderEndpoint("file://host1.athenz.com"));
        
        // other null and empty test cases
        assertTrue(zms.verifyProviderEndpoint(null));
        assertTrue(zms.verifyProviderEndpoint(""));
    }
    
    @Test
    public void testGetServerTemplateList() {

        ServerTemplateList list = zms.getServerTemplateList(mockDomRsrcCtx);
        assertNotNull(list);
        assertTrue(list.getTemplateNames().contains("platforms"));
        assertTrue(list.getTemplateNames().contains("vipng"));
        assertTrue(list.getTemplateNames().contains("user_provisioning"));
    }
    
    @Test
    public void testGetTemplateInvalid() {
        try {
            zms.getTemplate(mockDomRsrcCtx, "platforms test");
            fail();
        } catch (ResourceException ex) {
            assertEquals(400, ex.getCode());
        }
        
        try {
            zms.getTemplate(mockDomRsrcCtx, "invalid");
            fail();
        } catch (ResourceException ex) {
            assertEquals(404, ex.getCode());
        }
    }
    
    @Test
    public void testGetTemplate() {
        
        Template template = zms.getTemplate(mockDomRsrcCtx, "user_provisioning");
        assertNotNull(template);

        List<Role> roles = template.getRoles();
        assertNotNull(roles);
        assertEquals(3, roles.size());
        
        Role userRole = null;
        Role superuserRole = null;
        Role openstackReadersRole = null;
        for (Role role : roles) {
            switch (role.getName()) {
                case "_domain_:role.user":
                    userRole = role;
                    break;
                case "_domain_:role.superuser":
                    superuserRole = role;
                    break;
                case "_domain_:role.openstack_readers":
                    openstackReadersRole = role;
                    break;
            }
        }
        
        assertNotNull(userRole);
        assertNotNull(superuserRole);
        assertNotNull(openstackReadersRole);
        
        // openstack_readers role has 2 members
        
        assertEquals(2, openstackReadersRole.getRoleMembers().size());
        List<String> checkList = new ArrayList<>();
        checkList.add("sys.builder");
        checkList.add("sys.openstack");
        checkRoleMember(checkList, openstackReadersRole.getRoleMembers());
        
        // other roles have no members
        
        assertNull(userRole.getRoleMembers());
        assertNull(superuserRole.getRoleMembers());

        List<Policy> policies = template.getPolicies();
        assertNotNull(policies);
        assertEquals(3, policies.size());
        
        Policy userPolicy = null;
        Policy superuserPolicy = null;
        Policy openstackReadersPolicy = null;
        for (Policy policy : policies) {
            switch (policy.getName()) {
                case "_domain_:policy.user":
                    userPolicy = policy;
                    break;
                case "_domain_:policy.superuser":
                    superuserPolicy = policy;
                    break;
                case "_domain_:policy.openstack_readers":
                    openstackReadersPolicy = policy;
                    break;
            }
        }
        
        assertNotNull(userPolicy);
        assertNotNull(superuserPolicy);
        assertNotNull(openstackReadersPolicy);
        
        assertEquals(1, userPolicy.getAssertions().size());
        assertEquals(1, superuserPolicy.getAssertions().size());
        assertEquals(2, openstackReadersPolicy.getAssertions().size());

        template = zms.getTemplate(mockDomRsrcCtx, "vipng");
        assertNotNull(template);
        
        template = zms.getTemplate(mockDomRsrcCtx, "platforms");
        assertNotNull(template);
        
        template = zms.getTemplate(mockDomRsrcCtx, "VipNg");
        assertNotNull(template);

        assertEquals(10, template.getMetadata().getLatestVersion().intValue());
        assertEquals("2020-04-28T00:00:00.000Z", template.getMetadata().timestamp.toString());
        assertEquals("Vipng template", template.getMetadata().description);
        assertEquals("", template.getMetadata().keywordsToReplace);
        assertFalse(template.getMetadata().getAutoUpdate());
    }
    
    @Test
    public void testValidateSolutionTemplates() {
        final String caller = "testValidateDomainTemplates";
        List<String> templateNames = new ArrayList<>();
        templateNames.add("platforms");
        zms.validateSolutionTemplates(templateNames, caller);
        
        templateNames.add("vipng");
        zms.validateSolutionTemplates(templateNames, caller);

        templateNames.add("athenz");
        try {
            zms.validateSolutionTemplates(templateNames, caller);
            fail();
        } catch (ResourceException ex) {
            assertEquals(404, ex.getCode());
            assertTrue(ex.getMessage().contains("athenz"));
        }
    }

    @Test
    public void testPutDomainTemplateInvalidTemplate() {

        TestAuditLogger alogger = new TestAuditLogger();
        ZMSImpl zmsImpl = getZmsImpl(alogger);
        
        String domainName = "templatelist-invalid";
        TopLevelDomain dom1 = createTopLevelDomainObject(domainName,
                "Test Domain1", "testOrg", adminUser);
        zmsImpl.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);
        
        DomainTemplate templateList = new DomainTemplate();
        List<String> templates = new ArrayList<>();
        templates.add("test validate");
        templateList.setTemplateNames(templates);
        try {
            zmsImpl.putDomainTemplate(mockDomRsrcCtx, domainName, auditRef, templateList);
            fail();
        } catch (ResourceException ex) {
            assertEquals(400, ex.getCode());
        }
        
        zmsImpl.deleteTopLevelDomain(mockDomRsrcCtx, domainName, auditRef);
    }
    
    @Test
    public void testPutDomainTemplateNotFoundTemplate() {
        
        String domainName = "templatelist-invalid";
        TopLevelDomain dom1 = createTopLevelDomainObject(domainName,
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);
        
        DomainTemplate templateList = new DomainTemplate();
        List<String> templates = new ArrayList<>();
        templates.add("InvalidTemplate");
        templateList.setTemplateNames(templates);
        try {
            zms.putDomainTemplate(mockDomRsrcCtx, domainName, auditRef, templateList);
            fail();
        } catch (ResourceException ex) {
            assertEquals(404, ex.getCode());
        }
        
        zms.deleteTopLevelDomain(mockDomRsrcCtx, domainName, auditRef);
    }

    @Test
    public void testPutDomainTemplateSingleTemplate() {
        
        String domainName = "templatelist-single";
        TopLevelDomain dom1 = createTopLevelDomainObject(domainName,
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);
        
        DomainTemplate domTemplate = new DomainTemplate();
        List<String> templates = new ArrayList<>();
        templates.add("vipng");
        domTemplate.setTemplateNames(templates);
        
        zms.putDomainTemplate(mockDomRsrcCtx, domainName, auditRef, domTemplate);
        
        // verify that our role collection includes the roles defined in template
        
        List<String> names = zms.dbService.listRoles(domainName);
        assertEquals(3, names.size());
        assertTrue(names.contains("admin"));
        assertTrue(names.contains("vip_admin"));
        assertTrue(names.contains("sys_network_super_vip_admin"));
        
        Role role = zms.dbService.getRole(domainName, "vip_admin", false, false, false);
        assertEquals(domainName + ":role.vip_admin", role.getName());
        assertNull(role.getTrust());
        assertTrue(role.getRoleMembers().isEmpty());
        
        role = zms.dbService.getRole(domainName, "sys_network_super_vip_admin", false, false, false);
        assertEquals(domainName + ":role.sys_network_super_vip_admin", role.getName());
        assertEquals("sys.network", role.getTrust());
        
        // verify that our policy collections includes the policies defined in the template
        
        names = zms.dbService.listPolicies(domainName);
        assertEquals(3, names.size());
        assertTrue(names.contains("admin"));
        assertTrue(names.contains("vip_admin"));
        assertTrue(names.contains("sys_network_super_vip_admin"));
        
        Policy policy = zms.dbService.getPolicy(domainName, "vip_admin");
        assertEquals(domainName + ":policy.vip_admin", policy.getName());
        assertEquals(1, policy.getAssertions().size());
        Assertion assertion = policy.getAssertions().get(0);
        assertEquals("*", assertion.getAction());
        assertEquals(domainName + ":role.vip_admin", assertion.getRole());
        assertEquals(domainName + ":vip*", assertion.getResource());
        
        policy = zms.dbService.getPolicy(domainName, "sys_network_super_vip_admin");
        assertEquals(domainName + ":policy.sys_network_super_vip_admin", policy.getName());
        assertEquals(1, policy.getAssertions().size());
        assertion = policy.getAssertions().get(0);
        assertEquals("*", assertion.getAction());
        assertEquals(domainName + ":role.sys_network_super_vip_admin", assertion.getRole());
        assertEquals(domainName + ":vip*", assertion.getResource());

        // delete an applied service template
        //
        String templateName = "vipng";
        zms.deleteDomainTemplate(mockDomRsrcCtx, domainName, templateName, auditRef);
        
        // verify that our role collection does NOT include the roles defined in template
        
        names = zms.dbService.listRoles(domainName);
        assertEquals(1, names.size());
        assertTrue(names.contains("admin"));

        names = zms.dbService.listPolicies(domainName);
        assertEquals(1, names.size());
        assertTrue(names.contains("admin"));

        zms.deleteTopLevelDomain(mockDomRsrcCtx, domainName, auditRef);
    }
    
    @Test
    public void testPutDomainTemplateMultipleTemplates() {
        
        String domainName = "templatelist-multiple";
        TopLevelDomain dom1 = createTopLevelDomainObject(domainName,
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);
        
        DomainTemplate domTemplate = new DomainTemplate();
        List<String> templates = new ArrayList<>();
        templates.add("vipng");
        templates.add("platforms");
        templates.add("user_provisioning");
        domTemplate.setTemplateNames(templates);
        
        zms.putDomainTemplate(mockDomRsrcCtx, domainName, auditRef, domTemplate);
        
        // verify that our role collection includes the roles defined in template
        
        List<String> names = zms.dbService.listRoles(domainName);
        assertEquals(7, names.size());
        assertTrue(names.contains("admin"));
        assertTrue(names.contains("vip_admin"));
        assertTrue(names.contains("sys_network_super_vip_admin"));
        assertTrue(names.contains("platforms_deployer"));
        assertTrue(names.contains("user"));
        assertTrue(names.contains("superuser"));
        assertTrue(names.contains("openstack_readers"));

        Role role = zms.dbService.getRole(domainName, "openstack_readers", false, false, false);
        assertEquals(domainName + ":role.openstack_readers", role.getName());
        assertNull(role.getTrust());
        assertEquals(2, role.getRoleMembers().size());
        
        List<String> checkList = new ArrayList<>();
        checkList.add("sys.builder");
        checkList.add("sys.openstack");
        checkRoleMember(checkList, role.getRoleMembers());
        
        role = zms.dbService.getRole(domainName, "sys_network_super_vip_admin", false, false, false);
        assertEquals(domainName + ":role.sys_network_super_vip_admin", role.getName());
        assertEquals("sys.network", role.getTrust());
        
        // verify that our policy collections includes the policies defined in the template
        
        names = zms.dbService.listPolicies(domainName);
        assertEquals(7, names.size());
        assertTrue(names.contains("admin"));
        assertTrue(names.contains("vip_admin"));
        assertTrue(names.contains("sys_network_super_vip_admin"));
        assertTrue(names.contains("platforms_deploy"));
        assertTrue(names.contains("user"));
        assertTrue(names.contains("superuser"));
        assertTrue(names.contains("openstack_readers"));
        
        Policy policy = zms.dbService.getPolicy(domainName, "vip_admin");
        assertEquals(domainName + ":policy.vip_admin", policy.getName());
        assertEquals(1, policy.getAssertions().size());
        Assertion assertion = policy.getAssertions().get(0);
        assertEquals("*", assertion.getAction());
        assertEquals(domainName + ":role.vip_admin", assertion.getRole());
        assertEquals(domainName + ":vip*", assertion.getResource());
        
        policy = zms.dbService.getPolicy(domainName, "sys_network_super_vip_admin");
        assertEquals(domainName + ":policy.sys_network_super_vip_admin", policy.getName());
        assertEquals(1, policy.getAssertions().size());
        assertion = policy.getAssertions().get(0);
        assertEquals("*", assertion.getAction());
        assertEquals(domainName + ":role.sys_network_super_vip_admin", assertion.getRole());
        assertEquals(domainName + ":vip*", assertion.getResource());

        // delete applied service template
        //
        String templateName = "vipng";
        zms.deleteDomainTemplate(mockDomRsrcCtx, domainName, templateName, auditRef);
        
        // verify that our role collection does NOT include the vipng roles defined in template
        
        names = zms.dbService.listRoles(domainName);
        assertEquals(5, names.size());
        assertTrue(names.contains("admin"));
        assertTrue(names.contains("platforms_deployer"));
        assertTrue(names.contains("user"));
        assertTrue(names.contains("superuser"));
        assertTrue(names.contains("openstack_readers"));

        names = zms.dbService.listPolicies(domainName);
        assertEquals(5, names.size());
        assertTrue(names.contains("admin"));
        assertTrue(names.contains("platforms_deploy"));
        assertTrue(names.contains("user"));
        assertTrue(names.contains("superuser"));
        assertTrue(names.contains("openstack_readers"));

        // delete applied service template
        //
        templateName = "platforms";
        zms.deleteDomainTemplate(mockDomRsrcCtx, domainName, templateName, auditRef);
        
        // verify that our role collection does NOT include the platforms roles defined in template
        
        names = zms.dbService.listRoles(domainName);
        assertEquals(4, names.size());
        assertTrue(names.contains("admin"));
        assertTrue(names.contains("user"));
        assertTrue(names.contains("superuser"));
        assertTrue(names.contains("openstack_readers"));

        names = zms.dbService.listPolicies(domainName);
        assertEquals(4, names.size());
        assertTrue(names.contains("admin"));
        assertTrue(names.contains("user"));
        assertTrue(names.contains("superuser"));
        assertTrue(names.contains("openstack_readers"));

        // delete last applied service template
        //
        templateName = "user_provisioning";
        zms.deleteDomainTemplate(mockDomRsrcCtx, domainName, templateName, auditRef);
        
        // verify that our role collection does NOT include the user_provisioning roles defined in template
        
        names = zms.dbService.listRoles(domainName);
        assertEquals(1, names.size());
        assertTrue(names.contains("admin"));

        names = zms.dbService.listPolicies(domainName);
        assertEquals(1, names.size());
        assertTrue(names.contains("admin"));

        zms.deleteTopLevelDomain(mockDomRsrcCtx, domainName, auditRef);
    }
    
    @Test
    public void testPutDomainTemplateExtInvalidTemplate() {

        TestAuditLogger alogger = new TestAuditLogger();
        ZMSImpl zmsImpl = getZmsImpl(alogger);
        
        String domainName = "templatelist-invalid";
        TopLevelDomain dom1 = createTopLevelDomainObject(domainName,
                "Test Domain1", "testOrg", adminUser);
        zmsImpl.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);
        
        final String templateName = "test validate";
        DomainTemplate templateList = new DomainTemplate();
        List<String> templates = new ArrayList<>();
        templates.add(templateName);
        templateList.setTemplateNames(templates);
        try {
            zmsImpl.putDomainTemplateExt(mockDomRsrcCtx, domainName, templateName,
                    auditRef, templateList);
            fail();
        } catch (ResourceException ex) {
            assertEquals(400, ex.getCode());
        }
        
        zmsImpl.deleteTopLevelDomain(mockDomRsrcCtx, domainName, auditRef);
    }
    
    @Test
    public void testPutDomainTemplateExtNotFoundTemplate() {
        
        String domainName = "templatelist-invalid";
        TopLevelDomain dom1 = createTopLevelDomainObject(domainName,
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);
        
        final String templateName = "InvalidTemplate";
        DomainTemplate templateList = new DomainTemplate();
        List<String> templates = new ArrayList<>();
        templates.add(templateName);
        templateList.setTemplateNames(templates);
        try {
            zms.putDomainTemplateExt(mockDomRsrcCtx, domainName, templateName,
                    auditRef, templateList);
            fail();
        } catch (ResourceException ex) {
            assertEquals(404, ex.getCode());
        }
        
        zms.deleteTopLevelDomain(mockDomRsrcCtx, domainName, auditRef);
    }

    @Test
    public void testPutDomainTemplateExtMultipleTemplate() {
        
        String domainName = "templatelist-invalid";
        TopLevelDomain dom1 = createTopLevelDomainObject(domainName,
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);
        
        final String templateName = "vipng";
        DomainTemplate templateList = new DomainTemplate();
        List<String> templates = new ArrayList<>();
        templates.add(templateName);
        templates.add("pes");
        templateList.setTemplateNames(templates);
        try {
            zms.putDomainTemplateExt(mockDomRsrcCtx, domainName, templateName,
                    auditRef, templateList);
            fail();
        } catch (ResourceException ex) {
            assertEquals(400, ex.getCode());
        }
        
        zms.deleteTopLevelDomain(mockDomRsrcCtx, domainName, auditRef);
    }
    
    @Test
    public void testPutDomainTemplateExtSingleTemplate() {
        
        String domainName = "templatelist-single";
        TopLevelDomain dom1 = createTopLevelDomainObject(domainName,
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);
        
        final String templateName = "vipng";
        DomainTemplate domTemplate = new DomainTemplate();
        List<String> templates = new ArrayList<>();
        templates.add(templateName);
        domTemplate.setTemplateNames(templates);
        
        zms.putDomainTemplateExt(mockDomRsrcCtx, domainName, templateName,
                auditRef, domTemplate);
        
        // verify that our role collection includes the roles defined in template
        
        List<String> names = zms.dbService.listRoles(domainName);
        assertEquals(3, names.size());
        assertTrue(names.contains("admin"));
        assertTrue(names.contains("vip_admin"));
        assertTrue(names.contains("sys_network_super_vip_admin"));
        
        Role role = zms.dbService.getRole(domainName, "vip_admin", false, false, false);
        assertEquals(domainName + ":role.vip_admin", role.getName());
        assertNull(role.getTrust());
        assertTrue(role.getRoleMembers().isEmpty());
        
        role = zms.dbService.getRole(domainName, "sys_network_super_vip_admin", false, false, false);
        assertEquals(domainName + ":role.sys_network_super_vip_admin", role.getName());
        assertEquals("sys.network", role.getTrust());
        
        // verify that our policy collections includes the policies defined in the template
        
        names = zms.dbService.listPolicies(domainName);
        assertEquals(3, names.size());
        assertTrue(names.contains("admin"));
        assertTrue(names.contains("vip_admin"));
        assertTrue(names.contains("sys_network_super_vip_admin"));
        
        Policy policy = zms.dbService.getPolicy(domainName, "vip_admin");
        assertEquals(domainName + ":policy.vip_admin", policy.getName());
        assertEquals(1, policy.getAssertions().size());
        Assertion assertion = policy.getAssertions().get(0);
        assertEquals("*", assertion.getAction());
        assertEquals(domainName + ":role.vip_admin", assertion.getRole());
        assertEquals(domainName + ":vip*", assertion.getResource());
        
        policy = zms.dbService.getPolicy(domainName, "sys_network_super_vip_admin");
        assertEquals(domainName + ":policy.sys_network_super_vip_admin", policy.getName());
        assertEquals(1, policy.getAssertions().size());
        assertion = policy.getAssertions().get(0);
        assertEquals("*", assertion.getAction());
        assertEquals(domainName + ":role.sys_network_super_vip_admin", assertion.getRole());
        assertEquals(domainName + ":vip*", assertion.getResource());

        // delete an applied service template
        //
        zms.deleteDomainTemplate(mockDomRsrcCtx, domainName, templateName, auditRef);
        
        // verify that our role collection does NOT include the roles defined in template
        
        names = zms.dbService.listRoles(domainName);
        assertEquals(1, names.size());
        assertTrue(names.contains("admin"));

        names = zms.dbService.listPolicies(domainName);
        assertEquals(1, names.size());
        assertTrue(names.contains("admin"));

        zms.deleteTopLevelDomain(mockDomRsrcCtx, domainName, auditRef);
    }
    
    @Test
    public void testGetDomainTemplateListInvalid() {
        
        try {
            zms.getDomainTemplateList(mockDomRsrcCtx, "invalid_domain name");
            fail();
        } catch (ResourceException ex) {
            assertEquals(400, ex.getCode());
        }
        
        try {
            zms.getDomainTemplateList(mockDomRsrcCtx, "not_found_domain_name");
            fail();
        } catch (ResourceException ex) {
            assertEquals(404, ex.getCode());
        }
    }

    @Test
    public void testGetDomainTemplateList() {
        
        String domainName = "domaintemplatelist-valid";
        TopLevelDomain dom1 = createTopLevelDomainObject(domainName,
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);
        
        // initially no templates
        
        DomainTemplateList domaintemplateList = zms.getDomainTemplateList(mockDomRsrcCtx, domainName);
        List<String> templates = domaintemplateList.getTemplateNames();
        assertEquals(0, templates.size());
        
        // add a single template
        
        DomainTemplate domTemplate = new DomainTemplate();
        templates = new ArrayList<>();
        templates.add("vipng");
        domTemplate.setTemplateNames(templates);
        
        zms.putDomainTemplate(mockDomRsrcCtx, domainName, auditRef, domTemplate);
        
        domaintemplateList = zms.getDomainTemplateList(mockDomRsrcCtx, domainName);
        templates = domaintemplateList.getTemplateNames();
        assertEquals(1, templates.size());
        assertTrue(templates.contains("vipng"));
        
        // add 2 templates
        
        domTemplate = new DomainTemplate();
        templates = new ArrayList<>();
        templates.add("vipng");
        templates.add("platforms");
        domTemplate.setTemplateNames(templates);
        
        zms.putDomainTemplate(mockDomRsrcCtx, domainName, auditRef, domTemplate);
        
        domaintemplateList = zms.getDomainTemplateList(mockDomRsrcCtx, domainName);
        templates = domaintemplateList.getTemplateNames();
        assertEquals(2, templates.size());
        assertTrue(templates.contains("vipng"));
        assertTrue(templates.contains("platforms"));

        // add the same set of templates again and no change in results
        domTemplate = new DomainTemplate();
        domTemplate.setTemplateNames(templates);
        zms.putDomainTemplate(mockDomRsrcCtx, domainName, auditRef, domTemplate);
        
        domaintemplateList = zms.getDomainTemplateList(mockDomRsrcCtx, domainName);
        templates = domaintemplateList.getTemplateNames();
        assertEquals(2, templates.size());
        assertTrue(templates.contains("vipng"));
        assertTrue(templates.contains("platforms"));

        // delete an applied service template
        //
        String templateName = "vipng";
        zms.deleteDomainTemplate(mockDomRsrcCtx, domainName, templateName, auditRef);

        domaintemplateList = zms.getDomainTemplateList(mockDomRsrcCtx, domainName);
        templates = domaintemplateList.getTemplateNames();
        assertEquals(1, templates.size());
        assertTrue(templates.contains("platforms"));
        
        // delete last applied service template
        //
        templateName = "platforms";
        zms.deleteDomainTemplate(mockDomRsrcCtx, domainName, templateName, auditRef);

        domaintemplateList = zms.getDomainTemplateList(mockDomRsrcCtx, domainName);
        templates = domaintemplateList.getTemplateNames();
        assertTrue(templates.isEmpty());
        
        zms.deleteTopLevelDomain(mockDomRsrcCtx, domainName, auditRef);
    }
    
    @Test
    public void testPostSubDomainWithTemplates() {
        
        String domainName = "postsubdomain-withtemplate";
        TopLevelDomain dom1 = createTopLevelDomainObject(domainName,
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);
        
        SubDomain dom2 = createSubDomainObject("sub", domainName,
                "Test Domain2", "testOrg", adminUser);
        DomainTemplateList templateList = new DomainTemplateList();
        List<String> templates = new ArrayList<>();
        templates.add("vipng");
        templates.add("platforms");
        templates.add("user_provisioning");
        templateList.setTemplateNames(templates);
        dom2.setTemplates(templateList);
        
        Domain resDom1 = zms.postSubDomain(mockDomRsrcCtx, domainName, auditRef, dom2);
        assertNotNull(resDom1);
        
        String subDomainName = domainName + ".sub";
        
        // verify that our role collection includes the roles defined in template
        
        List<String> names = zms.dbService.listRoles(subDomainName);
        assertEquals(7, names.size());
        assertTrue(names.contains("admin"));
        assertTrue(names.contains("vip_admin"));
        assertTrue(names.contains("sys_network_super_vip_admin"));
        assertTrue(names.contains("platforms_deployer"));
        assertTrue(names.contains("user"));
        assertTrue(names.contains("superuser"));
        assertTrue(names.contains("openstack_readers"));

        Role role = zms.dbService.getRole(subDomainName, "openstack_readers", false, false, false);
        assertEquals(subDomainName + ":role.openstack_readers", role.getName());
        assertNull(role.getTrust());
        assertEquals(2, role.getRoleMembers().size());

        List<String> checkList = new ArrayList<>();
        checkList.add("sys.builder");
        checkList.add("sys.openstack");
        checkRoleMember(checkList, role.getRoleMembers());
        
        role = zms.dbService.getRole(subDomainName, "sys_network_super_vip_admin", false, false, false);
        assertEquals(subDomainName + ":role.sys_network_super_vip_admin", role.getName());
        assertEquals("sys.network", role.getTrust());
        
        // verify that our policy collections includes the policies defined in the template
        
        names = zms.dbService.listPolicies(subDomainName);
        assertEquals(7, names.size());
        assertTrue(names.contains("admin"));
        assertTrue(names.contains("vip_admin"));
        assertTrue(names.contains("sys_network_super_vip_admin"));
        assertTrue(names.contains("platforms_deploy"));
        assertTrue(names.contains("user"));
        assertTrue(names.contains("superuser"));
        assertTrue(names.contains("openstack_readers"));
        
        Policy policy = zms.dbService.getPolicy(subDomainName, "vip_admin");
        assertEquals(subDomainName + ":policy.vip_admin", policy.getName());
        assertEquals(1, policy.getAssertions().size());
        Assertion assertion = policy.getAssertions().get(0);
        assertEquals("*", assertion.getAction());
        assertEquals(subDomainName + ":role.vip_admin", assertion.getRole());
        assertEquals(subDomainName + ":vip*", assertion.getResource());
        
        policy = zms.dbService.getPolicy(subDomainName, "sys_network_super_vip_admin");
        assertEquals(subDomainName + ":policy.sys_network_super_vip_admin", policy.getName());
        assertEquals(1, policy.getAssertions().size());
        assertion = policy.getAssertions().get(0);
        assertEquals("*", assertion.getAction());
        assertEquals(subDomainName + ":role.sys_network_super_vip_admin", assertion.getRole());
        assertEquals(subDomainName + ":vip*", assertion.getResource());

        // verify the saved domain list
        
        DomainTemplateList domaintemplateList = zms.getDomainTemplateList(mockDomRsrcCtx, subDomainName);
        templates = domaintemplateList.getTemplateNames();
        assertEquals(3, templates.size());
        assertTrue(templates.contains("vipng"));
        assertTrue(templates.contains("platforms"));
        assertTrue(templates.contains("user_provisioning"));

        // delete an applied service template
        //
        String templateName = "vipng";
        zms.deleteDomainTemplate(mockDomRsrcCtx, subDomainName, templateName, auditRef);

        domaintemplateList = zms.getDomainTemplateList(mockDomRsrcCtx, subDomainName);
        templates = domaintemplateList.getTemplateNames();
        assertEquals(2, templates.size());
        assertTrue(templates.contains("platforms"));
        assertTrue(templates.contains("user_provisioning"));

        names = zms.dbService.listRoles(subDomainName);
        assertEquals(5, names.size());
        assertTrue(names.contains("admin"));
        assertTrue(names.contains("platforms_deployer"));
        assertTrue(names.contains("user"));
        assertTrue(names.contains("superuser"));
        assertTrue(names.contains("openstack_readers"));

        names = zms.dbService.listPolicies(subDomainName);
        assertEquals(5, names.size());
        assertTrue(names.contains("admin"));
        assertTrue(names.contains("platforms_deploy"));
        assertTrue(names.contains("user"));
        assertTrue(names.contains("superuser"));
        assertTrue(names.contains("openstack_readers"));
        
        // delete an applied service template
        //
        templateName = "platforms";
        zms.deleteDomainTemplate(mockDomRsrcCtx, subDomainName, templateName, auditRef);

        domaintemplateList = zms.getDomainTemplateList(mockDomRsrcCtx, subDomainName);
        templates = domaintemplateList.getTemplateNames();
        assertEquals(1, templates.size());
        assertTrue(templates.contains("user_provisioning"));

        names = zms.dbService.listRoles(subDomainName);
        assertEquals(4, names.size());
        assertTrue(names.contains("admin"));
        assertTrue(names.contains("user"));
        assertTrue(names.contains("superuser"));
        assertTrue(names.contains("openstack_readers"));

        names = zms.dbService.listPolicies(subDomainName);
        assertEquals(4, names.size());
        assertTrue(names.contains("admin"));
        assertTrue(names.contains("user"));
        assertTrue(names.contains("superuser"));
        assertTrue(names.contains("openstack_readers"));
        
        // delete last applied service template
        //
        templateName = "user_provisioning";
        zms.deleteDomainTemplate(mockDomRsrcCtx, subDomainName, templateName, auditRef);

        domaintemplateList = zms.getDomainTemplateList(mockDomRsrcCtx, subDomainName);
        templates = domaintemplateList.getTemplateNames();
        assertTrue(templates.isEmpty());

        names = zms.dbService.listRoles(subDomainName);
        assertEquals(1, names.size());
        assertTrue(names.contains("admin"));

        names = zms.dbService.listPolicies(subDomainName);
        assertEquals(1, names.size());
        assertTrue(names.contains("admin"));
        
        zms.deleteSubDomain(mockDomRsrcCtx, domainName, "sub", auditRef);
        zms.deleteTopLevelDomain(mockDomRsrcCtx, domainName, auditRef);
    }
    
    @Test
    public void testPutPolicyNoLoopbackNoSuchDomainError() {
        HttpServletRequest servletRequest = Mockito.mock(HttpServletRequest.class);
        Mockito.when(servletRequest.getRemoteAddr()).thenReturn("10.10.10.11");
        Mockito.when(servletRequest.isSecure()).thenReturn(true);

        TestAuditLogger alogger = new TestAuditLogger();
        ZMSImpl zmsObj = getZmsImpl(alogger);

        String userId = "user";
        Principal principal = SimplePrincipal.create("user", userId, "v=U1;d=user;n=user;s=signature", 0, null);
        ResourceContext context = createResourceContext(principal, servletRequest);
        String domainName = "DomainName";
        String policyName = "PolicyName";
        
        // Tests the putPolicy() condition: if (domain == null)...
        try {
            Policy policy = createPolicyObject(domainName, policyName);
            
            // should fail b/c we never created a top level domain.
            zmsObj.putPolicy(context, domainName, policyName, auditRef, policy);
            fail("requesterror not thrown.");
        } catch (ResourceException e) {
            assertEquals(e.getCode(), 404);
        }
    }
    
    @Test
    public void testPutPolicyLoopbackNoXFF_InconsistentNameError() {
        HttpServletRequest servletRequest = Mockito.mock(HttpServletRequest.class);
        Mockito.when(servletRequest.getRemoteAddr()).thenReturn("127.0.0.1");
        Mockito.when(servletRequest.isSecure()).thenReturn(true);

        TestAuditLogger alogger = new TestAuditLogger();
        ZMSImpl zmsObj = getZmsImpl(alogger);

        String userId = "user";
        Principal principal = SimplePrincipal.create("user", userId, "v=U1;d=user;n=user;s=signature", 0, null);
        ResourceContext context = createResourceContext(principal, servletRequest);
        String domainName = "DomainName";
        String policyName = "PolicyName";
        
        // Tests the putPolicy() condition : if (!policyResourceName(domainName, policyName).equals(policy.getName()))...
        try {
            Policy policy = createPolicyObject(domainName, policyName);
            
            zmsObj.putPolicy(context, domainName, "Bad" + policyName, auditRef, policy);
            fail("requesterror not thrown.");
        } catch (ResourceException e) {
            assertEquals(e.getCode(), 400);
        }
    }
    
    @Test
    public void testPutPolicyLoopbackXFFSingleValue() {
        HttpServletRequest servletRequest = Mockito.mock(HttpServletRequest.class);
        Mockito.when(servletRequest.getRemoteAddr()).thenReturn("127.0.0.1");
        Mockito.when(servletRequest.getHeader("X-Forwarded-For")).thenReturn("10.10.10.11");
        Mockito.when(servletRequest.isSecure()).thenReturn(true);

        TestAuditLogger alogger = new TestAuditLogger();
        ZMSImpl zmsObj = getZmsImpl(alogger);

        String userId = "user";
        Principal principal = SimplePrincipal.create("user", userId, "v=U1;d=user;n=user;s=signature", 0, null);
        ResourceContext context = createResourceContext(principal, servletRequest);
        String domainName = "DomainName";
        String policyName = "PolicyName";
        
        // Tests the putPolicy() condition : if (!policyResourceName(domainName, policyName).equals(policy.getName()))...
        try {
            Policy policy = createPolicyObject(domainName, policyName);
            
            zmsObj.putPolicy(context, domainName, "Bad" + policyName, auditRef, policy);
            fail("requesterror not thrown.");
        } catch (ResourceException e) {
            assertEquals(e.getCode(), 400);
        }
    }
    
    @Test
    public void testPutPolicyLoopbackXFFMultipleValues() {
        HttpServletRequest servletRequest = Mockito.mock(HttpServletRequest.class);
        Mockito.when(servletRequest.getRemoteAddr()).thenReturn("127.0.0.1");
        Mockito.when(servletRequest.getHeader("X-Forwarded-For")).thenReturn("10.10.10.11, 10.11.11.11, 10.12.12.12");
        Mockito.when(servletRequest.isSecure()).thenReturn(true);

        TestAuditLogger alogger = new TestAuditLogger();
        ZMSImpl zmsObj = getZmsImpl(alogger);

        String userId = "user";
        Principal principal = SimplePrincipal.create("user", userId, "v=U1;d=user;n=user;s=signature", 0, null);
        ResourceContext context = createResourceContext(principal, servletRequest);
        String domainName = "DomainName";
        String policyName = "PolicyName";
        
        // Tests the putPolicy() condition : if (!policyResourceName(domainName, policyName).equals(policy.getName()))...
        try {
            Policy policy = createPolicyObject(domainName, policyName);
            
            zmsObj.putPolicy(context, domainName, "Bad" + policyName, auditRef, policy);
            fail("requesterror not thrown.");
        } catch (ResourceException e) {
            assertEquals(e.getCode(), 400);
        }
    }
    
    @Test
    public void testRetrieveResourceDomainAssumeRoleWithTrust() {
        assertEquals("trustdomain", zms.retrieveResourceDomain("resource", "assume_role", "trustdomain"));
    }
    
    @Test
    public void testRetrieveResourceDomainAssumeRoleWithOutTrust() {
        assertEquals("domain1", zms.retrieveResourceDomain("domain1:resource", "assume_role", null));
    }
    
    @Test
    public void testRetrieveResourceDomainValidDomain() {
        assertEquals("domain1", zms.retrieveResourceDomain("domain1:resource", "read", null));
        assertEquals("domain1", zms.retrieveResourceDomain("domain1:resource", "read", "trustdomain"));
        assertEquals("domain1", zms.retrieveResourceDomain("domain1:a:b:c:d:e", "read", "trustdomain"));

    }
    
    @Test
    public void testRetrieveResourceDomainInvalidResource() {
        assertNull(zms.retrieveResourceDomain("domain1-invalid", "read", null));
    }

    @Test
    public void testLoadSolutionTemplatesInvalid() {
        System.setProperty(ZMSConsts.ZMS_PROP_SOLUTION_TEMPLATE_FNAME, "invalid-templates.json");
        zms.serverSolutionTemplates = null;
        zms.loadSolutionTemplates();
        assertNotNull(zms.serverSolutionTemplates);
        assertTrue(zms.serverSolutionTemplates.getTemplates().isEmpty());
        System.clearProperty(ZMSConsts.ZMS_PROP_SOLUTION_TEMPLATE_FNAME);
    }

    @Test
    public void testUnderscoreNotAllowed() {

        String domainName = "core-tech";
        String badDomainName = "core_tech";
        
        TopLevelDomain dom1 = createTopLevelDomainObject(domainName,
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        TopLevelDomain dom2 = createTopLevelDomainObject(badDomainName,
                "Test Domain1", "testOrg", adminUser);
        try {
            zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom2);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 400);
        }

        SubDomain sub = createSubDomainObject(badDomainName, domainName,
                "Test Domain2", "testOrg", adminUser);
        try {
            zms.postSubDomain(mockDomRsrcCtx, domainName, auditRef, sub);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 400);
        }
        
        UserDomain userDom = createUserDomainObject(badDomainName, "Test Domain1", "testOrg");
        try {
            zms.postUserDomain(mockDomRsrcCtx, badDomainName, auditRef, userDom);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 400);
        }
        
        zms.deleteTopLevelDomain(mockDomRsrcCtx, domainName, auditRef);
    }
    
    @Test
    public void testReadOnlyMode() {
        
        // first initialize our impl which would create our service

        //noinspection UnusedAssignment
        ZMSImpl zmsTest = zmsInit();
        
        // now we're going to create a new instance with read-only mode
        
        System.setProperty(ZMSConsts.ZMS_PROP_READ_ONLY_MODE, "true");
        
        zmsTest = new ZMSImpl();
        ZMSImpl.serverHostName = "localhost";

        TopLevelDomain dom1 = createTopLevelDomainObject("ReadOnlyDom1",
                "Test Domain1", "testOrg", adminUser);
        try {
            zmsTest.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 400);
            assertTrue(ex.getMessage().contains("Read-Only"));
        }

        try {
            zmsTest.deleteTopLevelDomain(mockDomRsrcCtx, "domain", auditRef);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 400);
            assertTrue(ex.getMessage().contains("Read-Only"));
        }

        try {
            zmsTest.postUserDomain(mockDomRsrcCtx, "user", auditRef, null);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 400);
            assertTrue(ex.getMessage().contains("Read-Only"));
        }

        try {
            zmsTest.deleteUserDomain(mockDomRsrcCtx, "user", auditRef);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 400);
            assertTrue(ex.getMessage().contains("Read-Only"));
        }

        try {
            zmsTest.postSubDomain(mockDomRsrcCtx, "athenz", auditRef, null);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 400);
            assertTrue(ex.getMessage().contains("Read-Only"));
        }

        try {
            zmsTest.deleteSubDomain(mockDomRsrcCtx, "athenz", "sub", auditRef);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 400);
            assertTrue(ex.getMessage().contains("Read-Only"));
        }

        try {
            zmsTest.putDomainTemplate(mockDomRsrcCtx, "athenz", auditRef, null);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 400);
            assertTrue(ex.getMessage().contains("Read-Only"));
        }

        try {
            zmsTest.putDomainTemplateExt(mockDomRsrcCtx, "athenz", "template", auditRef, null);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 400);
            assertTrue(ex.getMessage().contains("Read-Only"));
        }

        try {
            zmsTest.deleteDomainTemplate(mockDomRsrcCtx, "athenz", "template", auditRef);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 400);
            assertTrue(ex.getMessage().contains("Read-Only"));
        }

        try {
            zmsTest.putEntity(mockDomRsrcCtx, "athenz", "entity", auditRef, null);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 400);
            assertTrue(ex.getMessage().contains("Read-Only"));
        }

        try {
            zmsTest.deleteEntity(mockDomRsrcCtx, "athenz", "entity", auditRef);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 400);
            assertTrue(ex.getMessage().contains("Read-Only"));
        }

        Policy policy1 = createPolicyObject("ReadOnlyDom1", "Policy1");
        try {
            zmsTest.putPolicy(mockDomRsrcCtx, "ReadOnlyDom1", "Policy1", auditRef, policy1);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 400);
            assertTrue(ex.getMessage().contains("Read-Only"));
        }

        try {
            zmsTest.deletePolicy(mockDomRsrcCtx, "ReadOnlyDom1", "Policy1", auditRef);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 400);
            assertTrue(ex.getMessage().contains("Read-Only"));
        }

        try {
            zmsTest.putAssertion(mockDomRsrcCtx, "ReadOnlyDom1", "Policy1", auditRef, null);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 400);
            assertTrue(ex.getMessage().contains("Read-Only"));
        }

        try {
            zmsTest.deleteAssertion(mockDomRsrcCtx, "ReadOnlyDom1", "Policy1", 101L, auditRef);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 400);
            assertTrue(ex.getMessage().contains("Read-Only"));
        }

        Role role1 = createRoleObject("ReadOnlyDom1", "Role1", null,
                "user.joe", "user.jane");
        try {
            zmsTest.putRole(mockDomRsrcCtx, "ReadOnlyDom1", "Role1", auditRef, role1);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 400);
            assertTrue(ex.getMessage().contains("Read-Only"));
        }

        try {
            zmsTest.deleteRole(mockDomRsrcCtx, "ReadOnlyDom1", "Role1", auditRef);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 400);
            assertTrue(ex.getMessage().contains("Read-Only"));
        }

        try {
            zmsTest.putMembership(mockDomRsrcCtx, "ReadOnlyDom1", "Role1", "Member1", auditRef, null);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 400);
            assertTrue(ex.getMessage().contains("Read-Only"));
        }

        try {
            zmsTest.deleteMembership(mockDomRsrcCtx, "ReadOnlyDom1", "Role1", "Member1", auditRef);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 400);
            assertTrue(ex.getMessage().contains("Read-Only"));
        }

        ServiceIdentity service1 = createServiceObject("ReadOnlyDom1",
                "Service1", "http://localhost", "/usr/bin/java", "root",
                "users", "host1");

        try {
            zmsTest.putServiceIdentity(mockDomRsrcCtx, "ReadOnlyDom1", "Service1", auditRef, service1);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 400);
            assertTrue(ex.getMessage().contains("Read-Only"));
        }

        try {
            ServiceIdentitySystemMeta meta = new ServiceIdentitySystemMeta();
            zmsTest.putServiceIdentitySystemMeta(mockDomRsrcCtx, "ReadOnlyDom1", "Service1", "providerendpoint",
                    auditRef, meta);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 400);
            assertTrue(ex.getMessage().contains("Read-Only"));
        }

        try {
            zmsTest.deleteServiceIdentity(mockDomRsrcCtx, "ReadOnlyDom1", "Service1", auditRef);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 400);
            assertTrue(ex.getMessage().contains("Read-Only"));
        }

        try {
            zmsTest.putPublicKeyEntry(mockDomRsrcCtx, "ReadOnlyDom1", "Service1", "0", auditRef, null);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 400);
            assertTrue(ex.getMessage().contains("Read-Only"));
        }

        try {
            zmsTest.deletePublicKeyEntry(mockDomRsrcCtx, "ReadOnlyDom1", "Service1", "0", auditRef);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 400);
            assertTrue(ex.getMessage().contains("Read-Only"));
        }

        try {
            zmsTest.deleteDomainRoleMember(mockDomRsrcCtx, "dom1", "user1", auditRef);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 400);
            assertTrue(ex.getMessage().contains("Read-Only"));
        }

        try {
            zmsTest.deleteUser(mockDomRsrcCtx, "user1", auditRef);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 400);
            assertTrue(ex.getMessage().contains("Read-Only"));
        }

        try {
            zmsTest.putDomainMeta(mockDomRsrcCtx, "dom1", auditRef, null);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 400);
            assertTrue(ex.getMessage().contains("Read-Only"));
        }

        try {
            zmsTest.putDomainSystemMeta(mockDomRsrcCtx, "dom1", "account", auditRef, null);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 400);
            assertTrue(ex.getMessage().contains("Read-Only"));
        }

        try {
            zmsTest.putTenancy(mockDomRsrcCtx, "tenant", "provider.service", auditRef, null);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 400);
            assertTrue(ex.getMessage().contains("Read-Only"));
        }

        try {
            zmsTest.deleteTenancy(mockDomRsrcCtx, "tenant", "provider.service", auditRef);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 400);
            assertTrue(ex.getMessage().contains("Read-Only"));
        }

        try {
            zmsTest.putTenant(mockDomRsrcCtx, "provider", "service", "tenant", auditRef, null);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 400);
            assertTrue(ex.getMessage().contains("Read-Only"));
        }

        try {
            zmsTest.deleteTenant(mockDomRsrcCtx, "provider", "service", "tenant", auditRef);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 400);
            assertTrue(ex.getMessage().contains("Read-Only"));
        }

        try {
            zmsTest.putTenantResourceGroupRoles(mockDomRsrcCtx, "provider", "service", "tenant",
                    "resgroup", auditRef, null);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 400);
            assertTrue(ex.getMessage().contains("Read-Only"));
        }

        try {
            zmsTest.putProviderResourceGroupRoles(mockDomRsrcCtx, "tenant", "provider", "service",
                    "resgroup", auditRef, null);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 400);
            assertTrue(ex.getMessage().contains("Read-Only"));
        }

        try {
            zmsTest.deleteProviderResourceGroupRoles(mockDomRsrcCtx, "tenant", "provider", "service",
                    "resgroup", auditRef);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 400);
            assertTrue(ex.getMessage().contains("Read-Only"));
        }

        try {
            zmsTest.deleteTenantResourceGroupRoles(mockDomRsrcCtx, "provider", "service", "tenant",
                    "resgroup", auditRef);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 400);
            assertTrue(ex.getMessage().contains("Read-Only"));
        }

        try {
            zmsTest.putQuota(mockDomRsrcCtx, "domain", auditRef, null);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 400);
            assertTrue(ex.getMessage().contains("Read-Only"));
        }

        try {
            zmsTest.deleteQuota(mockDomRsrcCtx, "domain", auditRef);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 400);
            assertTrue(ex.getMessage().contains("Read-Only"));
        }

        try {
            zmsTest.putDefaultAdmins(mockDomRsrcCtx, "domain", auditRef, null);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 400);
            assertTrue(ex.getMessage().contains("Read-Only"));
        }

        try {
            RoleSystemMeta rsm = createRoleSystemMetaObject(true);
            zmsTest.putRoleSystemMeta(mockDomRsrcCtx, "domain", "role1", "auditenabled", auditRef, rsm);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 400);
            assertTrue(ex.getMessage().contains("Read-Only"));
        }

        try {
            RoleMeta rm = createRoleMetaObject(true);
            zmsTest.putRoleMeta(mockDomRsrcCtx, "domain", "role1", "auditenabled", rm);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 400);
            assertTrue(ex.getMessage().contains("Read-Only"));
        }

        try {
            zmsTest.putMembershipDecision(mockDomRsrcCtx, "readonlydom1", "role1", "member1", auditRef, null);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 400);
            assertTrue(ex.getMessage().contains("Read-Only"));
        }

        try {
            zmsTest.putRoleReview(mockDomRsrcCtx, "readonlydom1", "role1", auditRef, null);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 400);
            assertTrue(ex.getMessage().contains("Read-Only"));
        }

        try {
            zmsTest.deletePendingMembership(mockDomRsrcCtx, "readonlydom1", "role1", "member1", auditRef);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 400);
            assertTrue(ex.getMessage().contains("Read-Only"));
        }

        try {
            zmsTest.putGroup(mockDomRsrcCtx, "readonlydom1", "group1", auditRef, null);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 400);
            assertTrue(ex.getMessage().contains("Read-Only"));
        }

        try {
            zmsTest.deleteGroup(mockDomRsrcCtx, "readonlydom1", "group1", auditRef);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 400);
            assertTrue(ex.getMessage().contains("Read-Only"));
        }

        try {
            zmsTest.putGroupMembership(mockDomRsrcCtx, "readonlydom1", "group1", "user.joe", auditRef, null);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 400);
            assertTrue(ex.getMessage().contains("Read-Only"));
        }

        try {
            zmsTest.putGroupMembershipDecision(mockDomRsrcCtx, "readonlydom1", "group1", "user.joe", auditRef, null);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 400);
            assertTrue(ex.getMessage().contains("Read-Only"));
        }

        try {
            zmsTest.putGroupReview(mockDomRsrcCtx, "readonlydom1", "group1", auditRef, null);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 400);
            assertTrue(ex.getMessage().contains("Read-Only"));
        }

        try {
            zmsTest.putGroupMeta(mockDomRsrcCtx, "readonlydom1", "group1", auditRef, null);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 400);
            assertTrue(ex.getMessage().contains("Read-Only"));
        }

        try {
            zmsTest.putGroupSystemMeta(mockDomRsrcCtx, "readonlydom1", "group1", "attr", auditRef, null);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 400);
            assertTrue(ex.getMessage().contains("Read-Only"));
        }

        try {
            zmsTest.deleteGroupMembership(mockDomRsrcCtx, "readonlydom1", "group1", "user.joe", auditRef);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 400);
            assertTrue(ex.getMessage().contains("Read-Only"));
        }

        try {
            zmsTest.deletePendingGroupMembership(mockDomRsrcCtx, "readonlydom1", "group1", "user.joe", auditRef);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 400);
            assertTrue(ex.getMessage().contains("Read-Only"));
        }

        // now make sure we can read our sys.auth zms service
        
        ServiceIdentity serviceRes = zmsTest.getServiceIdentity(mockDomRsrcCtx, "sys.auth", "zms");
        assertNotNull(serviceRes);
        assertEquals(serviceRes.getName(), "sys.auth.zms");
        
        System.clearProperty(ZMSConsts.ZMS_PROP_READ_ONLY_MODE);
    }
    
    @Test
    public void testResourceContext() {
        RsrcCtxWrapper ctx = (RsrcCtxWrapper) zms.newResourceContext(mockServletRequest, mockServletResponse, "apiName");
        assertNotNull(ctx);
        assertNotNull(ctx.context());
        assertNull(ctx.principal());
        assertEquals(ctx.request(), mockServletRequest);
        assertEquals(ctx.response(), mockServletResponse);

        try {
            com.yahoo.athenz.common.server.rest.ResourceException restExc = new com.yahoo.athenz.common.server.rest.ResourceException(401, "failed struct");
            ctx.throwZmsException(restExc);
            fail();
        } catch (ResourceException ex) {
            assertEquals(401, ex.getCode());
            assertEquals( ((ResourceError) ex.data).message, "failed struct");
        }
    }

    @Test
    public void testAssertionMatchAuthenticatedRoles() {
        Role role = new Role().setName("domain:role.role1");
        ArrayList<Role> roles = new ArrayList<>();
        roles.add(role);

        ArrayList<String> authRoles = new ArrayList<>();
        authRoles.add("domain:role.role1");

        Assertion assertion = new Assertion();
        assertion.setAction("write");
        assertion.setResource("domain:db.write");
        assertion.setRole("domain:role.role1");

        assertTrue(zms.assertionMatch(assertion, "user.john", "write", "domain:db.write", "domain",
                roles, authRoles, null));

        // check case sensitive action and resource
        assertion = new Assertion();
        assertion.setAction("write");
        assertion.setResource("domain:db.write");
        assertion.setRole("domain:role.role1");

        assertTrue(zms.assertionMatch(assertion, "user.john", "write", "domain:db.write", "domain",
                roles, authRoles, null));

        // check case sensitive assertion
        assertion = new Assertion();
        assertion.setAction("WRITE");
        assertion.setResource("domain:db.WRITE");
        assertion.setRole("domain:role.role1");

        assertTrue(zms.assertionMatch(assertion, "user.john", "write", "domain:db.write", "domain",
                roles, authRoles, null));
    }

    @Test
    public void testMatchRoleNoRoles() {
        assertFalse(zms.matchRole("domain", new ArrayList<>(), "role", null));
    }
    
    @Test
    public void testMatchRoleNoRoleMatch() {
        assertFalse(zms.matchRole("domain", new ArrayList<>(), "domain:role\\.role2.*", null));
    }
    
    @Test
    public void testMatchRoleAuthRoleNoMatchShortName() {
        Role role = new Role().setName("domain:role.role1");
        ArrayList<Role> roles = new ArrayList<>();
        roles.add(role);
        
        ArrayList<String> authRoles = new ArrayList<>();
        authRoles.add("role3");
        
        assertFalse(zms.matchRole("domain", roles, "domain:role\\.role1.*", authRoles));
    }

    @Test
    public void testMatchRoleAuthRoleNoMatchFullName() {
        Role role = new Role().setName("domain:role.role1");
        ArrayList<Role> roles = new ArrayList<>();
        roles.add(role);

        ArrayList<String> authRoles = new ArrayList<>();
        authRoles.add("domain:role.role3");

        assertFalse(zms.matchRole("domain", roles, "domain:role\\.role1.*", authRoles));
    }

    @Test
    public void testMatchRoleNoMatchPattern() {
        Role role = new Role().setName("domain:role.role2");
        ArrayList<Role> roles = new ArrayList<>();
        roles.add(role);

        ArrayList<String> authRoles = new ArrayList<>();
        authRoles.add("role3");

        assertFalse(zms.matchRole("domain", roles, "domain:role\\.role1.*", authRoles));
    }

    @Test
    public void testMatchRoleShortName() {
        Role role = new Role().setName("domain:role.role1");
        ArrayList<Role> roles = new ArrayList<>();
        roles.add(role);
        
        ArrayList<String> authRoles = new ArrayList<>();
        authRoles.add("role1");
        
        assertTrue(zms.matchRole("domain", roles, "domain:role\\.role.*", authRoles));
    }

    @Test
    public void testMatchRoleFullName() {
        Role role = new Role().setName("domain:role.role1");
        ArrayList<Role> roles = new ArrayList<>();
        roles.add(role);

        ArrayList<String> authRoles = new ArrayList<>();
        authRoles.add("domain:role.role1");

        assertTrue(zms.matchRole("domain", roles, "domain:role\\.role.*", authRoles));
    }

    @Test
    public void testExtractDomainName() {
        assertEquals(zms.extractDomainName("domain:entity"), "domain");
        assertEquals(zms.extractDomainName("domain:entity:value2"), "domain");
        assertEquals(zms.extractDomainName("domain:https://web.athenz.com/data"), "domain");
    }
    
    @Test
    public void testServerInternalError() {
        
        RuntimeException ex = ZMSUtils.internalServerError("unit test", "tester");
        assertTrue(ex.getMessage().contains("{code: 500"));
    }
    
    @Test
    public void testGetSchema() {
        Schema schema = zms.getRdlSchema(mockDomRsrcCtx);
        assertNotNull(schema);
    }
    
    @Test
    public void testValidatePolicyAssertionsInValid() {
        
        // assertion missing domain name
        
        Assertion assertion = new Assertion();
        assertion.setAction("update");
        assertion.setEffect(AssertionEffect.ALLOW);
        assertion.setResource("resource1");
        assertion.setRole(ZMSUtils.roleResourceName("domain1", "role1"));

        List<Assertion> assertList = new ArrayList<>();
        assertList.add(assertion);
        
        try {
            zms.validatePolicyAssertions(assertList, "unitTest");
            fail();
        } catch (ResourceException ex) {
            assertEquals(400, ex.getCode());
        }
        
        // assertion with empty domain name
        
        assertion = new Assertion();
        assertion.setAction("update");
        assertion.setEffect(AssertionEffect.ALLOW);
        assertion.setResource(":resource1");
        assertion.setRole(ZMSUtils.roleResourceName("domain1", "role1"));
        
        assertList.clear();
        assertList.add(assertion);
        
        try {
            zms.validatePolicyAssertions(assertList, "unitTest");
            fail();
        } catch (ResourceException ex) {
            assertEquals(400, ex.getCode());
        }
        
        // assertion with invalid domain name
        
        assertion = new Assertion();
        assertion.setAction("update");
        assertion.setEffect(AssertionEffect.ALLOW);
        assertion.setResource("domain name:resource1");
        assertion.setRole(ZMSUtils.roleResourceName("domain1", "role1"));
        
        assertList.clear();
        assertList.add(assertion);
        
        try {
            zms.validatePolicyAssertions(assertList, "unitTest");
            fail();
        } catch (ResourceException ex) {
            assertEquals(400, ex.getCode());
        }
    }
    
    @Test
    public void testValidatePolicyAssertionInValid() {
        
        // assertion missing domain name
        
        Assertion assertion = new Assertion();
        assertion.setAction("update");
        assertion.setEffect(AssertionEffect.ALLOW);
        assertion.setResource("resource1");
        assertion.setRole(ZMSUtils.roleResourceName("domain1", "role1"));
        
        try {
            zms.validatePolicyAssertion(assertion, "unitTest");
            fail();
        } catch (ResourceException ex) {
            assertEquals(400, ex.getCode());
        }
        
        // assertion with empty domain name
        
        assertion = new Assertion();
        assertion.setAction("update");
        assertion.setEffect(AssertionEffect.ALLOW);
        assertion.setResource(":resource1");
        assertion.setRole(ZMSUtils.roleResourceName("domain1", "role1"));
        
        try {
            zms.validatePolicyAssertion(assertion, "unitTest");
            fail();
        } catch (ResourceException ex) {
            assertEquals(400, ex.getCode());
        }
        
        // assertion with invalid domain name
        
        assertion = new Assertion();
        assertion.setAction("update");
        assertion.setEffect(AssertionEffect.ALLOW);
        assertion.setResource("domain name:resource1");
        assertion.setRole(ZMSUtils.roleResourceName("domain1", "role1"));
        
        try {
            zms.validatePolicyAssertion(assertion, "unitTest");
            fail();
        } catch (ResourceException ex) {
            assertEquals(400, ex.getCode());
        }
        
        // assertion with invalid resource name
        
        assertion = new Assertion();
        assertion.setAction("update");
        assertion.setEffect(AssertionEffect.ALLOW);
        assertion.setResource("domain1:resource\t\ntest");
        assertion.setRole(ZMSUtils.roleResourceName("domain1", "role1"));
        
        try {
            zms.validatePolicyAssertion(assertion, "unitTest");
            fail();
        } catch (ResourceException ex) {
            assertEquals(400, ex.getCode());
        }
        
        // assertion with null action
        
        assertion = new Assertion();
        assertion.setAction(null);
        assertion.setEffect(AssertionEffect.ALLOW);
        assertion.setResource("domain1:resource1");
        assertion.setRole(ZMSUtils.roleResourceName("domain1", "role1"));
        
        try {
            zms.validatePolicyAssertion(assertion, "unitTest");
            fail();
        } catch (ResourceException ex) {
            assertEquals(400, ex.getCode());
        }
        
        // assertion with empty action
        
        assertion = new Assertion();
        assertion.setAction("");
        assertion.setEffect(AssertionEffect.ALLOW);
        assertion.setResource("domain1:resource1");
        assertion.setRole(ZMSUtils.roleResourceName("domain1", "role1"));
        
        try {
            zms.validatePolicyAssertion(assertion, "unitTest");
            fail();
        } catch (ResourceException ex) {
            assertEquals(400, ex.getCode());
        }
        
        // assertion with action containing control characters
        
        assertion = new Assertion();
        assertion.setAction("update\t");
        assertion.setEffect(AssertionEffect.ALLOW);
        assertion.setResource("domain1:resource1");
        assertion.setRole(ZMSUtils.roleResourceName("domain1", "role1"));
        
        try {
            zms.validatePolicyAssertion(assertion, "unitTest");
            fail();
        } catch (ResourceException ex) {
            assertEquals(400, ex.getCode());
        }
    }
    
    @Test
    public void testValidatePolicyAssertionsValid() {
        
        Assertion assertion = new Assertion();
        assertion.setAction("update");
        assertion.setEffect(AssertionEffect.ALLOW);
        assertion.setResource("domain1:resource1");
        assertion.setRole(ZMSUtils.roleResourceName("domain1", "role1"));

        List<Assertion> assertList = new ArrayList<>();
        assertList.add(assertion);
        
        assertion = new Assertion();
        assertion.setAction("update");
        assertion.setEffect(AssertionEffect.ALLOW);
        assertion.setResource("*:resource1");
        assertion.setRole(ZMSUtils.roleResourceName("domain1", "role1"));
        
        assertList.add(assertion);
        
        assertion = new Assertion();
        assertion.setAction("update");
        assertion.setEffect(AssertionEffect.ALLOW);
        assertion.setResource("domain1:");
        assertion.setRole(ZMSUtils.roleResourceName("domain1", "role1"));
        
        assertList.add(assertion);
        
        try {
            zms.validatePolicyAssertions(assertList, "unitTest");
        } catch (Exception ex) {
            fail(ex.getMessage());
        }
        
        // null should also be valid
        
        try {
            zms.validatePolicyAssertions(null, "unitTest");
        } catch (Exception ex) {
            fail(ex.getMessage());
        }
    }
    
    @Test
    public void testValidatePolicyAssertionValid() {
        
        Assertion assertion = new Assertion();
        assertion.setAction("update");
        assertion.setEffect(AssertionEffect.ALLOW);
        assertion.setResource("domain1:resource1");
        assertion.setRole(ZMSUtils.roleResourceName("domain1", "role1"));

        try {
            zms.validatePolicyAssertion(assertion, "unitTest");
        } catch (Exception ex) {
            fail(ex.getMessage());
        }
        
        assertion = new Assertion();
        assertion.setAction("update");
        assertion.setEffect(AssertionEffect.ALLOW);
        assertion.setResource("*:resource1");
        assertion.setRole(ZMSUtils.roleResourceName("domain1", "role1"));
        
        try {
            zms.validatePolicyAssertion(assertion, "unitTest");
        } catch (Exception ex) {
            fail(ex.getMessage());
        }
        
        assertion = new Assertion();
        assertion.setAction("update");
        assertion.setEffect(AssertionEffect.ALLOW);
        assertion.setResource("domain1:");
        assertion.setRole(ZMSUtils.roleResourceName("domain1", "role1"));
        
        try {
            zms.validatePolicyAssertion(assertion, "unitTest");
        } catch (Exception ex) {
            fail(ex.getMessage());
        }
    }
    
    @Test
    public void testSetupRoleListWithMembers() {

        String domainName = "setuprolelistwithmembers";
        TopLevelDomain dom1 = createTopLevelDomainObject(domainName,
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        Role role1 = createRoleObject(domainName, "Role1", null, "user.joe",
                "user.jane");
        zms.putRole(mockDomRsrcCtx, domainName, "Role1", auditRef, role1);

        Role role2 = createRoleObject(domainName, "Role2", null, "user.doe",
                "user.janie");
        zms.putRole(mockDomRsrcCtx, domainName, "Role2", auditRef, role2);

        Role role3 = createRoleObject(domainName, "Role3", "sys.auth", null, null);
        zms.putRole(mockDomRsrcCtx, domainName, "Role3", auditRef, role3);
        
        AthenzDomain domain = zms.getAthenzDomain(domainName, false);
        List<Role> roles = zms.setupRoleList(domain, Boolean.TRUE);
        assertEquals(4, roles.size()); // need to account for admin role
        
        boolean role1Check = false;
        boolean role2Check = false;
        boolean role3Check = false;
        
        for (Role role : roles) {
            switch (role.getName()) {
                case "setuprolelistwithmembers:role.role1":
                    List<String> checkList = new ArrayList<>();
                    checkList.add("user.joe");
                    checkList.add("user.jane");
                    checkRoleMember(checkList, role.getRoleMembers());
                    assertEquals(role.getRoleMembers().size(), 2);
                    assertNull(role.getTrust());
                    assertNotNull(role.getModified());
                    role1Check = true;
                    break;
                case "setuprolelistwithmembers:role.role2":
                    List<String> checkList2 = new ArrayList<>();
                    checkList2.add("user.doe");
                    checkList2.add("user.janie");
                    checkRoleMember(checkList2, role.getRoleMembers());
                    assertEquals(role.getRoleMembers().size(), 2);
                    assertNull(role.getTrust());
                    assertNotNull(role.getModified());
                    role2Check = true;
                    break;
                case "setuprolelistwithmembers:role.role3":
                    assertEquals(role.getTrust(), "sys.auth");
                    assertNull(role.getRoleMembers());
                    role3Check = true;
                    assertNotNull(role.getModified());
                    break;
            }
        }
        
        assertTrue(role1Check);
        assertTrue(role2Check);
        assertTrue(role3Check);

        zms.deleteTopLevelDomain(mockDomRsrcCtx, domainName, auditRef);
    }
    
    @Test
    public void testSetupRoleListWithOutMembers() {

        String domainName = "setuprolelistwithoutmembers";
        TopLevelDomain dom1 = createTopLevelDomainObject(domainName,
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        Role role1 = createRoleObject(domainName, "Role1", null, "user.joe",
                "user.jane");
        zms.putRole(mockDomRsrcCtx, domainName, "Role1", auditRef, role1);

        Role role2 = createRoleObject(domainName, "Role2", null, "user.doe",
                "user.janie");
        zms.putRole(mockDomRsrcCtx, domainName, "Role2", auditRef, role2);

        Role role3 = createRoleObject(domainName, "Role3", "sys.auth", null, null);
        zms.putRole(mockDomRsrcCtx, domainName, "Role3", auditRef, role3);

        Role role4 = createRoleObject(domainName, "Role4", null, "user.doe",
                "user.jane");
        zms.putRole(mockDomRsrcCtx, domainName, "Role4", auditRef, role4);

        RoleMeta rm = createRoleMetaObject(true);
        rm.setReviewEnabled(true);
        rm.setMemberExpiryDays(45);
        rm.setCertExpiryMins(55);
        rm.setServiceExpiryDays(45);
        rm.setTokenExpiryMins(65);
        rm.setMemberReviewDays(70);
        rm.setServiceReviewDays(80);
        rm.setSignAlgorithm("ec");
        zms.putRoleMeta(mockDomRsrcCtx, domainName, "role4", auditRef, rm);
        
        AthenzDomain domain = zms.getAthenzDomain(domainName, false);
        List<Role> roles = zms.setupRoleList(domain, Boolean.FALSE);
        assertEquals(5, roles.size()); // need to account for admin role
        
        boolean role1Check = false;
        boolean role2Check = false;
        boolean role3Check = false;
        boolean role4Check = false;
        
        for (Role role : roles) {
            switch (role.getName()) {
                case "setuprolelistwithoutmembers:role.role1":
                    assertNull(role.getRoleMembers());
                    assertNull(role.getTrust());
                    assertNotNull(role.getModified());
                    role1Check = true;
                    break;
                case "setuprolelistwithoutmembers:role.role2":
                    assertNull(role.getRoleMembers());
                    assertNull(role.getTrust());
                    assertNotNull(role.getModified());
                    role2Check = true;
                    break;
                case "setuprolelistwithoutmembers:role.role3":
                    assertEquals(role.getTrust(), "sys.auth");
                    assertNull(role.getRoleMembers());
                    role3Check = true;
                    assertNotNull(role.getModified());
                    break;
                case "setuprolelistwithoutmembers:role.role4":
                    assertNull(role.getRoleMembers());
                    assertNull(role.getTrust());
                    assertNotNull(role.getModified());
                    assertNull(role.getLastReviewedDate());
                    assertEquals(role.getMemberExpiryDays().intValue(), 45);
                    assertEquals(role.getCertExpiryMins().intValue(), 55);
                    assertEquals(role.getServiceExpiryDays().intValue(), 45);
                    assertEquals(role.getTokenExpiryMins().intValue(), 65);
                    assertEquals(role.getMemberReviewDays().intValue(), 70);
                    assertEquals(role.getServiceReviewDays().intValue(), 80);
                    assertNotNull(role.getSignAlgorithm());
                    assertTrue(role.getReviewEnabled());
                    assertTrue(role.getSelfServe());
                    assertNull(role.getAuditEnabled());
                    role4Check = true;
                    break;
            }
        }
        
        assertTrue(role1Check);
        assertTrue(role2Check);
        assertTrue(role3Check);
        assertTrue(role4Check);

        // we'll do the same check this time passing null
        // for the boolean flag instead of false
        
        roles = zms.setupRoleList(domain, null);
        assertEquals(5, roles.size()); // need to account for admin role
        
        role1Check = false;
        role2Check = false;
        role3Check = false;
        role4Check = false;
        
        for (Role role : roles) {
            switch (role.getName()) {
                case "setuprolelistwithoutmembers:role.role1":
                    assertNull(role.getRoleMembers());
                    assertNull(role.getTrust());
                    assertNotNull(role.getModified());
                    role1Check = true;
                    break;
                case "setuprolelistwithoutmembers:role.role2":
                    assertNull(role.getRoleMembers());
                    assertNull(role.getTrust());
                    assertNotNull(role.getModified());
                    role2Check = true;
                    break;
                case "setuprolelistwithoutmembers:role.role3":
                    assertEquals(role.getTrust(), "sys.auth");
                    assertNull(role.getRoleMembers());
                    role3Check = true;
                    assertNotNull(role.getModified());
                    break;
                case "setuprolelistwithoutmembers:role.role4":
                    assertNull(role.getRoleMembers());
                    assertNull(role.getTrust());
                    assertNotNull(role.getModified());
                    assertNull(role.getLastReviewedDate());
                    assertEquals(role.getMemberExpiryDays().intValue(), 45);
                    assertEquals(role.getCertExpiryMins().intValue(), 55);
                    assertEquals(role.getServiceExpiryDays().intValue(), 45);
                    assertEquals(role.getTokenExpiryMins().intValue(), 65);
                    assertEquals(role.getMemberReviewDays().intValue(), 70);
                    assertEquals(role.getServiceReviewDays().intValue(), 80);
                    assertNotNull(role.getSignAlgorithm());
                    assertTrue(role.getReviewEnabled());
                    assertTrue(role.getSelfServe());
                    role4Check = true;
                    break;
            }
        }
        
        assertTrue(role1Check);
        assertTrue(role2Check);
        assertTrue(role3Check);
        assertTrue(role4Check);
        
        zms.deleteTopLevelDomain(mockDomRsrcCtx, domainName, auditRef);
    }
    
    @Test
    public void testGetRoles() {

        String domainName = "getroles";
        TopLevelDomain dom1 = createTopLevelDomainObject(domainName,
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        Role role1 = createRoleObject(domainName, "Role1", null, "user.joe",
                "user.jane");
        zms.putRole(mockDomRsrcCtx, domainName, "Role1", auditRef, role1);

        Role role2 = createRoleObject(domainName, "Role2", null, "user.doe",
                "user.janie");
        zms.putRole(mockDomRsrcCtx, domainName, "Role2", auditRef, role2);

        Role role3 = createRoleObject(domainName, "Role3", "sys.auth", null, null);
        zms.putRole(mockDomRsrcCtx, domainName, "Role3", auditRef, role3);
        
        Roles roleList = zms.getRoles(mockDomRsrcCtx, domainName, Boolean.TRUE);
        List<Role> roles = roleList.getList();
        assertEquals(4, roles.size()); // need to account for admin role
        
        boolean role1Check = false;
        boolean role2Check = false;
        boolean role3Check = false;
        
        for (Role role : roles) {
            switch (role.getName()) {
                case "getroles:role.role1":
                    List<String> checkList = new ArrayList<>();
                    checkList.add("user.joe");
                    checkList.add("user.jane");
                    checkRoleMember(checkList, role.getRoleMembers());
                    assertEquals(role.getRoleMembers().size(), 2);
                    assertNull(role.getTrust());
                    assertNotNull(role.getModified());
                    role1Check = true;
                    break;
                case "getroles:role.role2":
                    List<String> checkList2 = new ArrayList<>();
                    checkList2.add("user.doe");
                    checkList2.add("user.janie");
                    checkRoleMember(checkList2, role.getRoleMembers());
                    assertEquals(role.getRoleMembers().size(), 2);
                    assertNull(role.getTrust());
                    assertNotNull(role.getModified());
                    role2Check = true;
                    break;
                case "getroles:role.role3":
                    assertEquals(role.getTrust(), "sys.auth");
                    assertNull(role.getRoleMembers());
                    role3Check = true;
                    assertNotNull(role.getModified());
                    break;
            }
        }
        
        assertTrue(role1Check);
        assertTrue(role2Check);
        assertTrue(role3Check);

        zms.deleteTopLevelDomain(mockDomRsrcCtx, domainName, auditRef);
    }
    
    @Test
    public void testGetRolesInvalidDomain() {

        final String domainName = "getrolesinvaliddomain";
        
        try {
            zms.getRoles(mockDomRsrcCtx, domainName, null);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 404);
        }
    }
    
    @Test
    public void testSetupPolicyListWithAssertions() {
        
        final String domainName = "setup-policy-with-assert";
        TopLevelDomain dom1 = createTopLevelDomainObject(domainName,
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        Policy policy1 = createPolicyObject(domainName, "policy1");
        zms.putPolicy(mockDomRsrcCtx, domainName, "policy1", auditRef, policy1);

        Policy policy2 = createPolicyObject(domainName, "policy2");
        zms.putPolicy(mockDomRsrcCtx, domainName, "policy2", auditRef, policy2);
        
        AthenzDomain domain = zms.getAthenzDomain(domainName, false);
        List<Policy> policies = zms.setupPolicyList(domain, Boolean.TRUE);
        assertEquals(3, policies.size()); // need to account for admin policy
        
        boolean policy1Check = false;
        boolean policy2Check = false;
        
        List<Assertion> testAssertions;
        for (Policy policy : policies) {
            switch (policy.getName()) {
                case "setup-policy-with-assert:policy.policy1":
                    testAssertions = policy.getAssertions();
                    assertEquals(testAssertions.size(), 1);
                    policy1Check = true;
                    break;
                case "setup-policy-with-assert:policy.policy2":
                    testAssertions = policy.getAssertions();
                    assertEquals(testAssertions.size(), 1);
                    policy2Check = true;
                    break;
            }
        }
        
        assertTrue(policy1Check);
        assertTrue(policy2Check);
        
        zms.deleteTopLevelDomain(mockDomRsrcCtx, domainName, auditRef);
    }
    
    @Test
    public void testGetPolicies() {
        
        final String domainName = "get-policies";
        TopLevelDomain dom1 = createTopLevelDomainObject(domainName,
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        Policy policy1 = createPolicyObject(domainName, "policy1");
        zms.putPolicy(mockDomRsrcCtx, domainName, "policy1", auditRef, policy1);

        Policy policy2 = createPolicyObject(domainName, "policy2");
        zms.putPolicy(mockDomRsrcCtx, domainName, "policy2", auditRef, policy2);

        Policies policyList = zms.getPolicies(mockDomRsrcCtx, domainName, Boolean.TRUE);
        List<Policy> policies = policyList.getList();
        assertEquals(3, policies.size()); // need to account for admin policy
        
        boolean policy1Check = false;
        boolean policy2Check = false;
        
        List<Assertion> testAssertions;
        for (Policy policy : policies) {
            switch (policy.getName()) {
                case "get-policies:policy.policy1":
                    testAssertions = policy.getAssertions();
                    assertEquals(testAssertions.size(), 1);
                    policy1Check = true;
                    break;
                case "get-policies:policy.policy2":
                    testAssertions = policy.getAssertions();
                    assertEquals(testAssertions.size(), 1);
                    policy2Check = true;
                    break;
            }
        }
        
        assertTrue(policy1Check);
        assertTrue(policy2Check);
        
        zms.deleteTopLevelDomain(mockDomRsrcCtx, domainName, auditRef);
    }
    
    @Test
    public void testGetPoliciesInvalidDomain() {

        String domainName = "get-policies-invalid-domain";
        
        try {
            zms.getPolicies(mockDomRsrcCtx, domainName, Boolean.TRUE);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 404);
        }
    }
    
    @Test
    public void testSetupPolicyListWithOutAssertions() {
        
        final String domainName = "setup-policy-without-assert";
        TopLevelDomain dom1 = createTopLevelDomainObject(domainName,
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        Policy policy1 = createPolicyObject(domainName, "policy1");
        zms.putPolicy(mockDomRsrcCtx, domainName, "policy1", auditRef, policy1);

        Policy policy2 = createPolicyObject(domainName, "policy2");
        zms.putPolicy(mockDomRsrcCtx, domainName, "policy2", auditRef, policy2);
        
        AthenzDomain domain = zms.getAthenzDomain(domainName, false);
        List<Policy> policies = zms.setupPolicyList(domain, Boolean.FALSE);
        assertEquals(3, policies.size()); // need to account for admin policy
        
        boolean policy1Check = false;
        boolean policy2Check = false;
        
        for (Policy policy : policies) {
            switch (policy.getName()) {
                case "setup-policy-without-assert:policy.policy1":
                    assertNull(policy.getAssertions());
                    policy1Check = true;
                    break;
                case "setup-policy-without-assert:policy.policy2":
                    assertNull(policy.getAssertions());
                    policy2Check = true;
                    break;
            }
        }
        
        assertTrue(policy1Check);
        assertTrue(policy2Check);
        
        zms.deleteTopLevelDomain(mockDomRsrcCtx, domainName, auditRef);
    }
    
    @Test
    public void testGetServiceIdentities() {
        
        final String domainName = "get-services";
        TopLevelDomain dom1 = createTopLevelDomainObject(domainName,
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        ServiceIdentity service1 = createServiceObject(domainName,
                "service1", "http://localhost", "/usr/bin/java", "root",
                "users", "host1");
        zms.putServiceIdentity(mockDomRsrcCtx, domainName, "service1", auditRef, service1);

        ServiceIdentity service2 = createServiceObject(domainName,
                "service2", "http://localhost", "/usr/bin/java", "yahoo",
                "users", "host2");
        zms.putServiceIdentity(mockDomRsrcCtx, domainName, "service2", auditRef, service2);
        
        ServiceIdentities serviceList = zms.getServiceIdentities(mockDomRsrcCtx, domainName,
                Boolean.TRUE, Boolean.TRUE);
        List<ServiceIdentity> services = serviceList.getList();
        assertEquals(2, services.size());
        
        boolean service1Check = false;
        boolean service2Check = false;
        
        for (ServiceIdentity service : services) {
            switch (service.getName()) {
                case "get-services.service1":
                    assertEquals(service.getExecutable(), "/usr/bin/java");
                    assertEquals(service.getUser(), "root");
                    assertEquals(service.getPublicKeys().size(), 2);
                    assertEquals(service.getHosts().size(), 1);
                    assertEquals(service.getHosts().get(0), "host1");
                    service1Check = true;
                    break;
                case "get-services.service2":
                    assertEquals(service.getExecutable(), "/usr/bin/java");
                    assertEquals(service.getUser(), "yahoo");
                    assertEquals(service.getPublicKeys().size(), 2);
                    assertEquals(service.getHosts().size(), 1);
                    assertEquals(service.getHosts().get(0), "host2");
                    service2Check = true;
                    break;
            }
        }
        
        assertTrue(service1Check);
        assertTrue(service2Check);
        
        zms.deleteTopLevelDomain(mockDomRsrcCtx, domainName, auditRef);
    }
    
    @Test
    public void testGetServiceIdentitiesInvalidDomain() {

        String domainName = "get-services-invalid-domain";
        
        try {
            zms.getServiceIdentities(mockDomRsrcCtx, domainName,
                    Boolean.TRUE, Boolean.TRUE);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 404);
        }
    }
    
    @Test
    public void testSetupServiceListWithKeysHosts() {
        
        final String domainName = "setup-service-keys-hosts";
        TopLevelDomain dom1 = createTopLevelDomainObject(domainName,
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        ServiceIdentity service1 = createServiceObject(domainName,
                "service1", "http://localhost", "/usr/bin/java", "root",
                "users", "host1");
        zms.putServiceIdentity(mockDomRsrcCtx, domainName, "service1", auditRef, service1);

        ServiceIdentity service2 = createServiceObject(domainName,
                "service2", "http://localhost", "/usr/bin/java", "yahoo",
                "users", "host2");
        zms.putServiceIdentity(mockDomRsrcCtx, domainName, "service2", auditRef, service2);
        
        AthenzDomain domain = zms.getAthenzDomain(domainName, false);
        List<ServiceIdentity> services = zms.setupServiceIdentityList(domain,
                Boolean.TRUE, Boolean.TRUE);
        assertEquals(2, services.size());
        
        boolean service1Check = false;
        boolean service2Check = false;
        
        for (ServiceIdentity service : services) {
            switch (service.getName()) {
                case "setup-service-keys-hosts.service1":
                    assertEquals(service.getExecutable(), "/usr/bin/java");
                    assertEquals(service.getUser(), "root");
                    assertEquals(service.getPublicKeys().size(), 2);
                    assertEquals(service.getHosts().size(), 1);
                    assertEquals(service.getHosts().get(0), "host1");
                    service1Check = true;
                    break;
                case "setup-service-keys-hosts.service2":
                    assertEquals(service.getExecutable(), "/usr/bin/java");
                    assertEquals(service.getUser(), "yahoo");
                    assertEquals(service.getPublicKeys().size(), 2);
                    assertEquals(service.getHosts().size(), 1);
                    assertEquals(service.getHosts().get(0), "host2");
                    service2Check = true;
                    break;
            }
        }
        
        assertTrue(service1Check);
        assertTrue(service2Check);
        
        zms.deleteTopLevelDomain(mockDomRsrcCtx, domainName, auditRef);
    }
    
    @Test
    public void testSetupServiceListWithOutKeysHosts() {
        
        final String domainName = "setup-service-without-keys-hosts";
        TopLevelDomain dom1 = createTopLevelDomainObject(domainName,
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        ServiceIdentity service1 = createServiceObject(domainName,
                "service1", "http://localhost", "/usr/bin/java", "root",
                "users", "host1");
        zms.putServiceIdentity(mockDomRsrcCtx, domainName, "service1", auditRef, service1);

        ServiceIdentity service2 = createServiceObject(domainName,
                "service2", "http://localhost", "/usr/bin/java", "yahoo",
                "users", "host2");
        zms.putServiceIdentity(mockDomRsrcCtx, domainName, "service2", auditRef, service2);
        
        AthenzDomain domain = zms.getAthenzDomain(domainName, false);
        List<ServiceIdentity> services = zms.setupServiceIdentityList(domain,
                Boolean.FALSE, Boolean.FALSE);
        assertEquals(2, services.size());
        
        boolean service1Check = false;
        boolean service2Check = false;
        
        for (ServiceIdentity service : services) {
            switch (service.getName()) {
                case "setup-service-without-keys-hosts.service1":
                    assertEquals(service.getExecutable(), "/usr/bin/java");
                    assertEquals(service.getUser(), "root");
                    assertNull(service.getPublicKeys());
                    assertNull(service.getHosts());
                    service1Check = true;
                    break;
                case "setup-service-without-keys-hosts.service2":
                    assertEquals(service.getExecutable(), "/usr/bin/java");
                    assertEquals(service.getUser(), "yahoo");
                    assertNull(service.getPublicKeys());
                    assertNull(service.getHosts());
                    service2Check = true;
                    break;
            }
        }
        
        assertTrue(service1Check);
        assertTrue(service2Check);
        
        zms.deleteTopLevelDomain(mockDomRsrcCtx, domainName, auditRef);
    }
    
    @Test
    public void testSetupServiceListWithKeysOnly() {
        
        final String domainName = "setup-service-keys-only";
        TopLevelDomain dom1 = createTopLevelDomainObject(domainName,
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        ServiceIdentity service1 = createServiceObject(domainName,
                "service1", "http://localhost", "/usr/bin/java", "root",
                "users", "host1");
        zms.putServiceIdentity(mockDomRsrcCtx, domainName, "service1", auditRef, service1);

        ServiceIdentity service2 = createServiceObject(domainName,
                "service2", "http://localhost", "/usr/bin/java", "yahoo",
                "users", "host2");
        zms.putServiceIdentity(mockDomRsrcCtx, domainName, "service2", auditRef, service2);
        
        AthenzDomain domain = zms.getAthenzDomain(domainName, false);
        List<ServiceIdentity> services = zms.setupServiceIdentityList(domain,
                Boolean.TRUE, Boolean.FALSE);
        assertEquals(2, services.size());
        
        boolean service1Check = false;
        boolean service2Check = false;
        
        for (ServiceIdentity service : services) {
            switch (service.getName()) {
                case "setup-service-keys-only.service1":
                    assertEquals(service.getExecutable(), "/usr/bin/java");
                    assertEquals(service.getUser(), "root");
                    assertEquals(service.getPublicKeys().size(), 2);
                    assertNull(service.getHosts());
                    service1Check = true;
                    break;
                case "setup-service-keys-only.service2":
                    assertEquals(service.getExecutable(), "/usr/bin/java");
                    assertEquals(service.getUser(), "yahoo");
                    assertEquals(service.getPublicKeys().size(), 2);
                    assertNull(service.getHosts());
                    service2Check = true;
                    break;
            }
        }
        
        assertTrue(service1Check);
        assertTrue(service2Check);
        
        zms.deleteTopLevelDomain(mockDomRsrcCtx, domainName, auditRef);
    }
    
    @Test
    public void testSetupServiceListWithHostsOnly() {
        
        final String domainName = "setup-service-hosts-only";
        TopLevelDomain dom1 = createTopLevelDomainObject(domainName,
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        ServiceIdentity service1 = createServiceObject(domainName,
                "service1", "http://localhost", "/usr/bin/java", "root",
                "users", "host1");
        zms.putServiceIdentity(mockDomRsrcCtx, domainName, "service1", auditRef, service1);

        ServiceIdentity service2 = createServiceObject(domainName,
                "service2", "http://localhost", "/usr/bin/java", "yahoo",
                "users", "host2");
        zms.putServiceIdentity(mockDomRsrcCtx, domainName, "service2", auditRef, service2);
        
        AthenzDomain domain = zms.getAthenzDomain(domainName, false);
        List<ServiceIdentity> services = zms.setupServiceIdentityList(domain,
                Boolean.FALSE, Boolean.TRUE);
        assertEquals(2, services.size());
        
        boolean service1Check = false;
        boolean service2Check = false;
        
        for (ServiceIdentity service : services) {
            switch (service.getName()) {
                case "setup-service-hosts-only.service1":
                    assertEquals(service.getExecutable(), "/usr/bin/java");
                    assertEquals(service.getUser(), "root");
                    assertNull(service.getPublicKeys());
                    assertEquals(service.getHosts().size(), 1);
                    assertEquals(service.getHosts().get(0), "host1");
                    service1Check = true;
                    break;
                case "setup-service-hosts-only.service2":
                    assertEquals(service.getExecutable(), "/usr/bin/java");
                    assertEquals(service.getUser(), "yahoo");
                    assertNull(service.getPublicKeys());
                    assertEquals(service.getHosts().size(), 1);
                    assertEquals(service.getHosts().get(0), "host2");
                    service2Check = true;
                    break;
            }
        }
        
        assertTrue(service1Check);
        assertTrue(service2Check);
        
        zms.deleteTopLevelDomain(mockDomRsrcCtx, domainName, auditRef);
    }
    
    @Test
    public void testGetAssertion() {

        final String domainName = "get-assertion";
        TopLevelDomain dom1 = createTopLevelDomainObject(domainName,
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        Policy policy = createPolicyObject(domainName, "policy1");
        zms.putPolicy(mockDomRsrcCtx, domainName, "policy1", auditRef, policy);

        Policy policyRes = zms.getPolicy(mockDomRsrcCtx, domainName, "policy1");
        Long assertionId = policyRes.getAssertions().get(0).getId();

        Assertion assertion = zms.getAssertion(mockDomRsrcCtx, domainName, "policy1", assertionId);
        assertNotNull(assertion);
        assertEquals(assertion.getAction(), "*");
        assertEquals(assertion.getResource(), domainName + ":*");
       
        zms.deleteTopLevelDomain(mockDomRsrcCtx, domainName, auditRef);
    }

    @Test
    public void testGetAssertionCaseSensitive() {

        final String domainName = "get-assertion";
        TopLevelDomain dom1 = createTopLevelDomainObject(domainName,
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        // Create case-sensitive policy
        Policy policy = createPolicyObject(domainName, "policy1", "Role1", "ActioN1", "GeT-AssertioN:SomeResource", AssertionEffect.ALLOW);
        policy.setCaseSensitive(true);

        // Create case-insensitive policy
        Policy policy2 = createPolicyObject(domainName, "policy2", "Role1", "ActioN2", "GeT-AssertioN:SomeResource2", AssertionEffect.ALLOW);

        zms.putPolicy(mockDomRsrcCtx, domainName, "policy1", auditRef, policy);
        zms.putPolicy(mockDomRsrcCtx, domainName, "policy2", auditRef, policy2);

        Policy policyRes = zms.getPolicy(mockDomRsrcCtx, domainName, "policy1");
        Long assertionId = policyRes.getAssertions().get(0).getId();

        Assertion assertion = zms.getAssertion(mockDomRsrcCtx, domainName, "policy1", assertionId);
        assertNotNull(assertion);
        assertEquals(assertion.getAction(), "ActioN1");
        assertEquals(assertion.getResource(), domainName + ":SomeResource");

        policyRes = zms.getPolicy(mockDomRsrcCtx, domainName, "policy2");
        assertionId = policyRes.getAssertions().get(0).getId();

        assertion = zms.getAssertion(mockDomRsrcCtx, domainName, "policy2", assertionId);
        assertNotNull(assertion);
        assertEquals(assertion.getAction(), "action2");
        assertEquals(assertion.getResource(), domainName + ":someresource2");

        zms.deleteTopLevelDomain(mockDomRsrcCtx, domainName, auditRef);
    }

    @Test
    public void testGetAssertionMultiple() {

        final String domainName = "get-assertion-multiple";
        TopLevelDomain dom1 = createTopLevelDomainObject(domainName,
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        Policy policy = createPolicyObject(domainName, "policy1");
        Assertion assertion = new Assertion();
        assertion.setAction("update");
        assertion.setEffect(AssertionEffect.ALLOW);
        assertion.setResource(domainName + ":resource");
        assertion.setRole(ZMSUtils.roleResourceName(domainName, "admin"));

        // Create case-sensitive assertion
        Assertion assertion2 = new Assertion();
        assertion2.setAction("UpdatE2");
        assertion2.setEffect(AssertionEffect.ALLOW);
        assertion2.setResource(domainName + ":ResourcE2");
        assertion2.setRole(ZMSUtils.roleResourceName(domainName, "admin"));
        assertion2.setCaseSensitive(true);

        // Put both assertions
        policy.getAssertions().add(assertion);
        policy.getAssertions().add(assertion2);

        zms.putPolicy(mockDomRsrcCtx, domainName, "policy1", auditRef, policy);

        Policy policyRes = zms.getPolicy(mockDomRsrcCtx, domainName, "policy1");
        List<Assertion> testAssertions = new ArrayList<>();

        Long assertionId = policyRes.getAssertions().get(0).getId();
        Assertion testAssertion = zms.getAssertion(mockDomRsrcCtx, domainName, "policy1", assertionId);
        assertNotNull(testAssertion);
        testAssertions.add(testAssertion);
       
        assertionId = policyRes.getAssertions().get(1).getId();
        testAssertion = zms.getAssertion(mockDomRsrcCtx, domainName, "policy1", assertionId);
        assertNotNull(testAssertion);
        testAssertions.add(testAssertion);

        assertionId = policyRes.getAssertions().get(2).getId();
        testAssertion = zms.getAssertion(mockDomRsrcCtx, domainName, "policy1", assertionId);
        assertNotNull(testAssertion);
        testAssertions.add(testAssertion);

        boolean assert1Check = false;
        boolean assert2Check = false;
        boolean assert3Check = false;
        for (Assertion testAssert : testAssertions) {
            switch (testAssert.getAction()) {
                case "*":
                    assertEquals(testAssert.getResource(), domainName + ":*");
                    assert1Check = true;
                    break;
                case "update":
                    assertEquals(testAssert.getResource(), domainName + ":resource");
                    assert2Check = true;
                    break;
                case "UpdatE2":
                    assertEquals(testAssert.getResource(), domainName + ":ResourcE2");
                    assert3Check = true;
                    break;
            }
        }
        assertTrue(assert1Check);
        assertTrue(assert2Check);
        assertTrue(assert3Check);

        zms.deleteTopLevelDomain(mockDomRsrcCtx, domainName, auditRef);
    }
    
    @Test
    public void testGetAssertionUnknownId() {

        final String domainName = "get-assertion-invalid";
        TopLevelDomain dom1 = createTopLevelDomainObject(domainName,
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        Policy policy = createPolicyObject(domainName, "policy1");
        zms.putPolicy(mockDomRsrcCtx, domainName, "policy1", auditRef, policy);

        try {
            zms.getAssertion(mockDomRsrcCtx, domainName, "policy1", 1L);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 404);
        }
       
        zms.deleteTopLevelDomain(mockDomRsrcCtx, domainName, auditRef);
    }
    
    @Test
    public void testPutAssertion() {

        final String domainName = "put-assertion";
        TopLevelDomain dom1 = createTopLevelDomainObject(domainName,
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        Policy policy = createPolicyObject(domainName, "policy1");
        zms.putPolicy(mockDomRsrcCtx, domainName, "policy1", auditRef, policy);

        Assertion assertion = new Assertion();
        assertion.setAction("update");
        assertion.setEffect(AssertionEffect.ALLOW);
        assertion.setResource(domainName + ":resource");
        assertion.setRole(ZMSUtils.roleResourceName(domainName, "admin"));

        // add the assertion
        
        assertion = zms.putAssertion(mockDomRsrcCtx, domainName, "policy1", auditRef, assertion);
        
        // verity that the return assertion object has the id set
        
        assertNotNull(assertion.getId());
        
        // validate that both assertions exist
        
        Policy policyRes = zms.getPolicy(mockDomRsrcCtx, domainName, "policy1");
        
        boolean assert1Check = false;
        boolean assert2Check = false;
        for (Assertion testAssert : policyRes.getAssertions()) {
            switch (testAssert.getAction()) {
                case "*":
                    assertEquals(testAssert.getResource(), domainName + ":*");
                    assert1Check = true;
                    break;
                case "update":
                    assertEquals(testAssert.getResource(), domainName + ":resource");
                    assert2Check = true;
                    break;
            }
        }
        assertTrue(assert1Check);
        assertTrue(assert2Check);
        
        zms.deleteTopLevelDomain(mockDomRsrcCtx, domainName, auditRef);
    }

    @Test
    public void testPutAssertionCaseSensitive() {

        final String domainName = "put-assertion";
        TopLevelDomain dom1 = createTopLevelDomainObject(domainName,
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        Policy policy = createPolicyObject(domainName, "policy1");
        zms.putPolicy(mockDomRsrcCtx, domainName, "policy1", auditRef, policy);

        Assertion assertion = new Assertion();
        assertion.setAction("Update");
        assertion.setEffect(AssertionEffect.ALLOW);
        assertion.setResource(domainName + ":Resource");
        assertion.setRole(ZMSUtils.roleResourceName(domainName, "admin"));
        assertion.setCaseSensitive(true);

        // add the assertion

        assertion = zms.putAssertion(mockDomRsrcCtx, domainName, "policy1", auditRef, assertion);

        // verity that the return assertion object has the id set

        assertNotNull(assertion.getId());

        // validate that both assertions exist

        Policy policyRes = zms.getPolicy(mockDomRsrcCtx, domainName, "policy1");

        boolean assert1Check = false;
        boolean assert2Check = false;
        for (Assertion testAssert : policyRes.getAssertions()) {
            switch (testAssert.getAction()) {
                case "*":
                    assertEquals(testAssert.getResource(), domainName + ":*");
                    assert1Check = true;
                    break;
                case "Update":
                    assertEquals(testAssert.getResource(), domainName + ":Resource");
                    assert2Check = true;
                    break;
            }
        }
        assertTrue(assert1Check);
        assertTrue(assert2Check);

        zms.deleteTopLevelDomain(mockDomRsrcCtx, domainName, auditRef);
    }

    @Test
    public void testAddDefaultAdminAssertion() {

        final String domainName = "put-default-assertion";
        final String policyName = "policy1";
        TopLevelDomain dom1 = createTopLevelDomainObject(domainName,
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        // add an empty policy to the domain

        Policy policy = createPolicyObject(domainName, policyName);
        zms.putPolicy(mockDomRsrcCtx, domainName, policyName, auditRef, policy);

        // add invalid assertions that will be skipped by the call
        // and we'll add the default assertion

        List<Assertion> assertList = new ArrayList<>();

        Assertion assertion = new Assertion();
        assertion.setResource(null);
        assertList.add(assertion);

        assertion = new Assertion();
        assertion.setResource(domainName + ":test");
        assertion.setAction(null);
        assertList.add(assertion);

        assertion = new Assertion();
        assertion.setResource(domainName + ":test");
        assertion.setAction("update");
        assertion.setRole(null);
        assertList.add(assertion);

        assertion = new Assertion();
        assertion.setResource(domainName + ":test");
        assertion.setAction("update");
        assertion.setRole("admin");
        assertList.add(assertion);

        assertion = new Assertion();
        assertion.setResource(domainName + ":test");
        assertion.setAction("update");
        assertion.setRole(ZMSUtils.roleResourceName(domainName, "admin"));
        assertion.setEffect(AssertionEffect.DENY);
        assertList.add(assertion);

        // add the assertion

        policy.setAssertions(assertList);
        zms.addDefaultAdminAssertion(mockDomRsrcCtx, domainName, policy, auditRef, "unit-test");

        // validate admin policy is correct set with assertion

        Policy policyRes = zms.getPolicy(mockDomRsrcCtx, domainName, "admin");

        boolean assert1Check = false;
        for (Assertion testAssert : policyRes.getAssertions()) {
            if ("*".equals(testAssert.getAction())) {
                assertEquals(testAssert.getResource(), domainName + ":*");
                assert1Check = true;
                break;
            }
        }
        assertTrue(assert1Check);

        zms.deleteTopLevelDomain(mockDomRsrcCtx, domainName, auditRef);
    }

    @Test
    public void testPutAssertionAdminReject() {

        final String domainName = "put-assertion-admin";
        TopLevelDomain dom1 = createTopLevelDomainObject(domainName,
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        Assertion assertion = new Assertion();
        assertion.setAction("update");
        assertion.setEffect(AssertionEffect.ALLOW);
        assertion.setResource(domainName + ":resource");
        assertion.setRole(ZMSUtils.roleResourceName(domainName, "admin"));
        
        try {
            zms.putAssertion(mockDomRsrcCtx, domainName, "admin", auditRef, assertion);
            fail();
        } catch (ResourceException ex) {
            assertTrue(ex.getMessage().contains("admin policy cannot be modified"), ex.getMessage());
        }
        
        zms.deleteTopLevelDomain(mockDomRsrcCtx, domainName, auditRef);
    }
    
    @Test
    public void testPutAssertionUnknownPolicy() {

        final String domainName = "put-assertion-unknown";
        TopLevelDomain dom1 = createTopLevelDomainObject(domainName,
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        Assertion assertion = new Assertion();
        assertion.setAction("update");
        assertion.setEffect(AssertionEffect.ALLOW);
        assertion.setResource(domainName + ":resource");
        assertion.setRole(ZMSUtils.roleResourceName(domainName, "admin"));

        // add the assertion which should fail due to unknown policy name
        
        try {
            zms.putAssertion(mockDomRsrcCtx, domainName, "policy2", auditRef, assertion);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 404);
        }
        
        zms.deleteTopLevelDomain(mockDomRsrcCtx, domainName, auditRef);
    }
    
    @Test
    public void testDeleteAssertionSingle() {

        final String domainName = "delete-assertion-single";
        TopLevelDomain dom1 = createTopLevelDomainObject(domainName,
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        Policy policy = createPolicyObject(domainName, "policy1");
        zms.putPolicy(mockDomRsrcCtx, domainName, "policy1", auditRef, policy);

        // now let's delete the assertion directly
        
        Policy policyRes = zms.getPolicy(mockDomRsrcCtx, domainName, "policy1");
        Long assertionId = policyRes.getAssertions().get(0).getId();

        zms.deleteAssertion(mockDomRsrcCtx, domainName, "policy1", assertionId, auditRef);
        
        policyRes = zms.getPolicy(mockDomRsrcCtx, domainName, "policy1");
        assertEquals(policyRes.getAssertions().size(), 0);
        
        zms.deleteTopLevelDomain(mockDomRsrcCtx, domainName, auditRef);
    }
    
    @Test
    public void testDeleteAssertionMultiple() {
        
        final String domainName = "delete-assertion-multiple";
        TopLevelDomain dom1 = createTopLevelDomainObject(domainName,
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        Policy policy = createPolicyObject(domainName, "policy1");
        Assertion assertion = new Assertion();
        assertion.setAction("update");
        assertion.setEffect(AssertionEffect.ALLOW);
        assertion.setResource(domainName + ":resource");
        assertion.setRole(ZMSUtils.roleResourceName(domainName, "admin"));
        policy.getAssertions().add(assertion);
        zms.putPolicy(mockDomRsrcCtx, domainName, "policy1", auditRef, policy);

        Policy policyRes = zms.getPolicy(mockDomRsrcCtx, domainName, "policy1");

        // we are going to delete assertion at index 0
        
        Long assertionId = policyRes.getAssertions().get(0).getId();
        zms.deleteAssertion(mockDomRsrcCtx, domainName, "policy1", assertionId, auditRef);

        // remember the assertion action for index 1
        
        String action = policyRes.getAssertions().get(1).getAction();
        
        // fetch the policy again and verify the action
        
        policyRes = zms.getPolicy(mockDomRsrcCtx, domainName, "policy1");
        assertEquals(policyRes.getAssertions().size(), 1);
        assertEquals(policyRes.getAssertions().get(0).getAction(), action);
        
        zms.deleteTopLevelDomain(mockDomRsrcCtx, domainName, auditRef);
    }
    
    @Test
    public void testDeleteAssertionAdminReject() {

        final String domainName = "delete-assertion-admin";
        TopLevelDomain dom1 = createTopLevelDomainObject(domainName,
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);
        
        try {
            zms.deleteAssertion(mockDomRsrcCtx, domainName, "admin", 101L, auditRef);
            fail();
        } catch (ResourceException ex) {
            assertTrue(ex.getMessage().contains("admin policy cannot be modified"), ex.getMessage());
        }
        
        zms.deleteTopLevelDomain(mockDomRsrcCtx, domainName, auditRef);
    }
    
    @Test
    public void testDeleteAssertionUnknown() {

        final String domainName = "delete-assertion-unknown";
        TopLevelDomain dom1 = createTopLevelDomainObject(domainName,
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        Policy policy = createPolicyObject(domainName, "policy1");
        zms.putPolicy(mockDomRsrcCtx, domainName, "policy1", auditRef, policy);

        // delete the assertion which should fail due to unknown policy name
        
        try {
            zms.deleteAssertion(mockDomRsrcCtx, domainName, "policy2", 1L, auditRef);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 404);
        }
        
        // delete the assertion which should fail due to unknown assertion id
        
        try {
            zms.deleteAssertion(mockDomRsrcCtx, domainName, "policy1", 1L, auditRef);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 400);
        }
        
        zms.deleteTopLevelDomain(mockDomRsrcCtx, domainName, auditRef);
    }
    
    @Test
    public void testGetPolicyListWithoutAssertionId() {
        
        assertNull(zms.getPolicyListWithoutAssertionId(null));
        
        List<Policy> emptyList = new ArrayList<>();
        List<Policy> result = zms.getPolicyListWithoutAssertionId(emptyList);
        assertTrue(result.isEmpty());
        
        final String domainName = "assertion-test";
        Policy policy = createPolicyObject(domainName, "policy1");
        policy.setCaseSensitive(true);
        Assertion assertion = new Assertion();
        assertion.setAction("update");
        assertion.setEffect(AssertionEffect.ALLOW);
        assertion.setResource(domainName + ":resource");
        assertion.setRole(ZMSUtils.roleResourceName(domainName, "admin"));
        assertion.setId(101L);
        assertion.setCaseSensitive(true);
        policy.getAssertions().add(assertion);
        
        List<Policy> policyList = new ArrayList<>();
        policyList.add(policy);
        
        result = zms.getPolicyListWithoutAssertionId(policyList);
        assertEquals(result.size(), 1);
        Assertion testAssertion = result.get(0).getAssertions().get(0);
        assertNull(testAssertion.getId());
        assertEquals(assertion.getAction(), "update");
        assertEquals(assertion.getEffect(), AssertionEffect.ALLOW);
        assertEquals(assertion.getResource(), domainName + ":resource");
        assertEquals(assertion.getRole(), ZMSUtils.roleResourceName(domainName, "admin"));

        // Verify case-sensitivity isn't returned
        assertNull(result.get(0).getCaseSensitive());
        assertNull(testAssertion.getCaseSensitive());
    }
    
    @Test
    public void testIsConsistentRoleName() {
        
        Role role = new Role();
        
        role.setName("domain1:role.role1");
        assertTrue(zms.isConsistentRoleName("domain1", "role1", role));
        
        // local name behavior
        
        role.setName("role1");
        assertTrue(zms.isConsistentRoleName("domain1", "role1", role));
        assertEquals(role.getName(), "domain1:role.role1");
        
        // inconsistent behavior
        
        role.setName("domain1:role.role1");
        assertFalse(zms.isConsistentRoleName("domain1", "role2", role));
        
        role.setName("role1");
        assertFalse(zms.isConsistentRoleName("domain1", "role2", role));
    }
    
    @Test
    public void testIsConsistentPolicyName() {
        
        Policy policy = new Policy();
        
        policy.setName("domain1:policy.policy1");
        assertTrue(zms.isConsistentPolicyName("domain1", "policy1", policy));
        
        // local name behavior
        
        policy.setName("policy1");
        assertTrue(zms.isConsistentPolicyName("domain1", "policy1", policy));
        assertEquals(policy.getName(), "domain1:policy.policy1");
        
        // inconsistent behavior
        
        policy.setName("domain1:policy.policy1");
        assertFalse(zms.isConsistentPolicyName("domain1", "policy2", policy));
        
        policy.setName("policy1");
        assertFalse(zms.isConsistentPolicyName("domain1", "policy2", policy));
    }
    
    @Test
    public void testGetDomainListNotNull() {
        Authority userAuthority = new com.yahoo.athenz.common.server.debug.DebugUserAuthority();
        String userId = "user1";
        Principal principal = SimplePrincipal.create("user", userId, userId + ":password", 0, userAuthority);
        assertNotNull(principal);
        ((SimplePrincipal) principal).setUnsignedCreds(userId);
        ResourceContext rsrcCtx1 = createResourceContext(principal);
        zms.getDomainList(rsrcCtx1, 100, null, null, 100, "account", 224, "roleMem1", "role1", null);
    }

    @Test
    public void testDeleteUserDomainNull() {
        Authority userAuthority = new com.yahoo.athenz.common.server.debug.DebugUserAuthority();
        String userId = "user1";
        Principal principal = SimplePrincipal.create("user", userId, userId + ":password", 0, userAuthority);
        assertNotNull(principal);
        ((SimplePrincipal) principal).setUnsignedCreds(userId);
        ResourceContext rsrcCtx1 = createResourceContext(principal);
        try {
            zms.deleteUserDomain(rsrcCtx1, null, null);
            fail();
        } catch (ResourceException ex) {
            assertTrue(true);
        }
    }

    @Test
    public void testDeleteDomainTemplateNull() {
        Authority userAuthority = new com.yahoo.athenz.common.server.debug.DebugUserAuthority();
        String userId = "user1";
        Principal principal = SimplePrincipal.create("user", userId, userId + ":password", 0, userAuthority);
        assertNotNull(principal);
        ((SimplePrincipal) principal).setUnsignedCreds(userId);
        ResourceContext rsrcCtx1 = createResourceContext(principal);
        try {
            zms.deleteDomainTemplate(rsrcCtx1, "dom1", null, "zms");
            fail();
        } catch (ResourceException ex) {
            assertTrue(true);
        }
    }
    
    @Test
    public void testIsAllowedResourceLookForAllUsers() {
        Authority principalAuthority = new com.yahoo.athenz.common.server.debug.DebugPrincipalAuthority();
        Principal principal1 = principalAuthority.authenticate("v=U1;d=user;n=user1;s=signature",
                "10.11.12.13", "GET", null);
        assertFalse(zms.isAllowedResourceLookForAllUsers(principal1));
    }
    
    @Test
    public void testDeleteDomainTemplate() {
        Authority principalAuthority = new com.yahoo.athenz.common.server.debug.DebugPrincipalAuthority();
        Principal principal1 = principalAuthority.authenticate("v=U1;d=user;n=user1;s=signature",
                "10.11.12.13", "GET", null);
        ResourceContext rsrcCtx1 = createResourceContext(principal1);
        try{
            zms.deleteDomainTemplate(rsrcCtx1, null, null, null);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 400);
        }
    }

    @Test
    public void testPutTenantResourceGroupRolesNull() {
        Authority principalAuthority = new com.yahoo.athenz.common.server.debug.DebugPrincipalAuthority();
        Principal principal1 = principalAuthority.authenticate("v=U1;d=user;n=user1;s=signature",
                "10.11.12.13", "GET", null);
        ResourceContext rsrcCtx1 = createResourceContext(principal1);
        TenantResourceGroupRoles tenantResource = new TenantResourceGroupRoles();
        try{
            zms.putTenantResourceGroupRoles(rsrcCtx1, null, null, null, null, null, tenantResource);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 400);
        }
    }
    
    @Test
    public void testDeleteTenantResourceGroupRolesNull() {
        Authority principalAuthority = new com.yahoo.athenz.common.server.debug.DebugPrincipalAuthority();
        Principal principal1 = principalAuthority.authenticate("v=U1;d=user;n=user1;s=signature",
                "10.11.12.13", "GET", null);
        ResourceContext rsrcCtx1 = createResourceContext(principal1);
        try{
            zms.deleteTenantResourceGroupRoles(rsrcCtx1, null, null, null, null, null);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 400);
        }
    }

    @Test
    public void testGetResourceAccessListFailure() {
        Authority principalAuthority = new com.yahoo.athenz.common.server.debug.DebugPrincipalAuthority();
        Principal principal1 = principalAuthority.authenticate("v=U1;d=user;n=user1;s=signature",
                "10.11.12.13", "GET", null);
        ResourceContext rsrcCtx1 = createResourceContext(principal1);
        try{
            zms.getResourceAccessList(rsrcCtx1, "principal", "UPDATE");
            fail();
        } catch(Exception ex) {
            assertTrue(true);
        }
    }

    @Test
    public void testGetResourceAccessList() {

        ZMSImpl zmsImpl = zmsInit();

        DBService dbService = Mockito.mock(DBService.class);
        Mockito.when(dbService.getResourceAccessList("sys.zts", "update"))
                .thenReturn(new ResourceAccessList());
        zmsImpl.dbService = dbService;

        Authority principalAuthority = new com.yahoo.athenz.common.server.debug.DebugPrincipalAuthority();
        Principal sysPrincipal = principalAuthority.authenticate("v=U1;d=sys;n=zts;s=signature",
                "10.11.12.13", "GET", null);
        ResourceContext rsrcCtx = createResourceContext(sysPrincipal);
        ResourceAccessList accessList = zmsImpl.getResourceAccessList(rsrcCtx, "sys.zts", "UPDATE");
        assertNotNull(accessList);

        zmsImpl.objectStore.clearConnections();
    }

    @Test
    public void testGetResourceAccessListNullPrincipalAction() {

        Policy policy1 = createPolicyObject("sys.auth", "resource-access");
        zms.putPolicy(mockDomRsrcCtx, "sys.auth", "resource-access", auditRef, policy1);

        Role role1 = createRoleObject("sys.auth", "Role1", null, "sys.zts", null);
        zms.putRole(mockDomRsrcCtx, "sys.auth", "Role1", auditRef, role1);

        AthenzDomain domain = zms.getAthenzDomain("sys.auth", false);

        // now create our mock zms and db service objects

        ZMSImpl zmsImpl = zmsInit();

        DBService dbService = Mockito.mock(DBService.class);
        Mockito.when(dbService.getResourceAccessList(null, null))
                .thenReturn(new ResourceAccessList());
        Mockito.when(dbService.getAthenzDomain("sys.auth", false)).thenReturn(domain);

        zmsImpl.dbService = dbService;

        Authority principalAuthority = new com.yahoo.athenz.common.server.debug.DebugPrincipalAuthority();
        Principal sysPrincipal = principalAuthority.authenticate("v=U1;d=sys;n=zts;s=signature",
                "10.11.12.13", "GET", null);
        ResourceContext rsrcCtx = createResourceContext(sysPrincipal);
        ResourceAccessList accessList = zmsImpl.getResourceAccessList(rsrcCtx, null, null);
        assertNotNull(accessList);

        zmsImpl.objectStore.clearConnections();
    }

    @Test
    public void testGetResourceAccessListNullPrincipalActionNotAuthorized() {

        Policy policy1 = createPolicyObject("sys.auth", "resource-access");
        zms.putPolicy(mockDomRsrcCtx, "sys.auth", "resource-access", auditRef, policy1);

        // we're authorizing sys.auth.zts and not sys.zts that our principal is using

        Role role1 = createRoleObject("sys.auth", "Role1", null, "sys.auth.zts", null);
        zms.putRole(mockDomRsrcCtx, "sys.auth", "Role1", auditRef, role1);

        AthenzDomain domain = zms.getAthenzDomain("sys.auth", false);

        // now create our mock zms and db service objects

        ZMSImpl zmsImpl = zmsInit();

        DBService dbService = Mockito.mock(DBService.class);
        Mockito.when(dbService.getResourceAccessList(null, null))
                .thenReturn(new ResourceAccessList());
        Mockito.when(dbService.getAthenzDomain("sys.auth", false)).thenReturn(domain);

        zmsImpl.dbService = dbService;

        Authority principalAuthority = new com.yahoo.athenz.common.server.debug.DebugPrincipalAuthority();
        Principal sysPrincipal = principalAuthority.authenticate("v=U1;d=sys;n=zts;s=signature",
                "10.11.12.13", "GET", null);
        ResourceContext rsrcCtx = createResourceContext(sysPrincipal);

        try {
            zmsImpl.getResourceAccessList(rsrcCtx, null, null);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), ResourceException.FORBIDDEN);
        }

        zmsImpl.objectStore.clearConnections();
    }

    @Test
    public void testDeleteProviderResourceGroupRolesNull() {
        Authority principalAuthority = new com.yahoo.athenz.common.server.debug.DebugPrincipalAuthority();
        Principal principal1 = principalAuthority.authenticate("v=U1;d=user;n=user1;s=signature",
                "10.11.12.13", "GET", null);
        ResourceContext rsrcCtx1 = createResourceContext(principal1);
        try{
            zms.deleteProviderResourceGroupRoles(rsrcCtx1, null, null, null, null, null);
            fail();
        } catch(Exception ex) {
            assertTrue(true);
        }
    }

    @DataProvider(name = "roles")
    public static Object[][] getRoles() {
        final String memberName="member1";
        final String memberNameToSearch="notFound";
        final Timestamp expiredTimestamp = Timestamp.fromMillis(System.currentTimeMillis() - 10000);
        final Timestamp notExpiredTimestamp = Timestamp.fromMillis(System.currentTimeMillis() + 10000);
        
        return new Object[][] {
            //expired
            {memberName, memberName, expiredTimestamp, true, false},
            //not expired
            {memberName, memberName, notExpiredTimestamp, true, true},
            //not found
            {memberName, memberNameToSearch, notExpiredTimestamp, true, false},
            //set not filled which means no members are defined
            {memberName, memberName, notExpiredTimestamp, false, false},
            //null expiration
            {memberName, memberName, null, true, true}, 
        };
    }

    @Test(dataProvider = "roles")
    public void testIsMemberOfRole(final String memeberName, final String memberNameToSearch,
            Timestamp expiredTimestamp, boolean setRoleMembers, boolean isMember) {
        //Construct roleMembers
        List<RoleMember> roleMembers = new ArrayList<>();
        RoleMember roleMember = new RoleMember();
        roleMember.setMemberName(memeberName);
        roleMember.setExpiration(expiredTimestamp);
        roleMembers.add(roleMember);

        Role role = new Role();
        if (setRoleMembers) {
            role.setRoleMembers(roleMembers);
        }
        boolean actual = zms.isMemberOfRole(role, memberNameToSearch);
        assertEquals(actual, isMember);
    }
    
    @Test
    public void testLogPrincipalEmpty() {
        
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        ResourceContext ctx = zms.newResourceContext(request, response, "apiName");
        zms.logPrincipal(ctx);
        assertTrue(request.getAttributes().isEmpty());
    }
    
    @Test
    public void testIsSysAdminUserInvalidDomain() {
        Authority principalAuthority = new com.yahoo.athenz.common.server.debug.DebugPrincipalAuthority();
        Principal principal = SimplePrincipal.create("sports", "nhl", "v=S1;d=sports;n=nhl;s=signature",
                0, principalAuthority);
        assertNotNull(principal);
        assertFalse(zms.isSysAdminUser(principal));
    }
    
    @Test
    public void testMemberNameMatch() {
        assertTrue(zms.memberNameMatch("*", "user.joe"));
        assertTrue(zms.memberNameMatch("*", "athenz.service.storage"));
        assertTrue(zms.memberNameMatch("user.*", "user.joe"));
        assertTrue(zms.memberNameMatch("athenz.*", "athenz.service.storage"));
        assertTrue(zms.memberNameMatch("athenz.service*", "athenz.service.storage"));
        assertTrue(zms.memberNameMatch("athenz.service*", "athenz.service-storage"));
        assertTrue(zms.memberNameMatch("athenz.service*", "athenz.service"));
        assertTrue(zms.memberNameMatch("user.joe", "user.joe"));
        
        assertFalse(zms.memberNameMatch("user.*", "athenz.joe"));
        assertFalse(zms.memberNameMatch("athenz.*", "athenztest.joe"));
        assertFalse(zms.memberNameMatch("athenz.service*", "athenz.servic"));
        assertFalse(zms.memberNameMatch("athenz.service*", "athenz.servictag"));
        assertFalse(zms.memberNameMatch("user.joe", "user.joel"));
    }
    
    @Test
    public void testGetUserList() {

        ZMSTestUtils.cleanupNotAdminUsers(zms, adminUser, mockDomRsrcCtx);

        String domainName = "listusers1";
        
        TopLevelDomain dom1 = createTopLevelDomainObject(domainName,
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        TopLevelDomain dom2 = createTopLevelDomainObject("listusersports",
                "Test Domain2", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom2);
        
        TopLevelDomain dom3 = createTopLevelDomainObject("listuserweather",
                "Test Domain3", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom3);
        
        Role role1 = createRoleObject(domainName, "role1", null,
                "user.joe", "user.janie");
        zms.putRole(mockDomRsrcCtx, domainName, "role1", auditRef, role1);
        
        Role role2 = createRoleObject(domainName, "role2", null,
                "user.joe", "listusersports.jane");
        zms.putRole(mockDomRsrcCtx, domainName, "role2", auditRef, role2);

        Role role3 = createRoleObject(domainName, "role3", null,
                "user.jack", "listuserweather.jane");
        zms.putRole(mockDomRsrcCtx, domainName, "role3", auditRef, role3);
        
        Role role4 = createRoleObject("listusersports", "role4", null,
                "user.ana", "user.janie");
        zms.putRole(mockDomRsrcCtx, "listusersports", "role4", auditRef, role4);
        
        UserList userList = zms.getUserList(mockDomRsrcCtx);
        List<String> users = userList.getNames();
        assertTrue(users.contains("user.testadminuser"));
        assertTrue(users.contains("user.janie"));
        assertTrue(users.contains("user.ana"));
        assertTrue(users.contains("user.jack"));
        assertTrue(users.contains("user.joe"));

        zms.deleteTopLevelDomain(mockDomRsrcCtx, domainName, auditRef);
        zms.deleteTopLevelDomain(mockDomRsrcCtx, "listusersports", auditRef);
        zms.deleteTopLevelDomain(mockDomRsrcCtx, "listuserweather", auditRef);
    }

    @Test
    public void testDeleteUser() {

        String domainName = "deleteuser1";

        ZMSTestUtils.cleanupNotAdminUsers(zms, adminUser, mockDomRsrcCtx);

        TopLevelDomain dom1 = createTopLevelDomainObject(domainName,
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        TopLevelDomain dom2 = createTopLevelDomainObject("deleteusersports",
                "Test Domain2", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom2);
        
        SubDomain subDom1 = createSubDomainObject("jack", "user",
                "Test SubDomain2", "testOrg", adminUser);
        zms.postSubDomain(mockDomRsrcCtx, "user", auditRef, subDom1);
        
        SubDomain subDom2 = createSubDomainObject("sub1", "user.jack",
                "Test SubDomain21", "testOrg", adminUser);
        zms.postSubDomain(mockDomRsrcCtx, "user.jack", auditRef, subDom2);
        
        Role role1 = createRoleObject(domainName, "role1", null,
                "user.joe", "user.jack.sub1.service");
        zms.putRole(mockDomRsrcCtx, domainName, "role1", auditRef, role1);
        
        Role role2 = createRoleObject(domainName, "role2", null,
                "user.joe", "deleteusersports.jane");
        zms.putRole(mockDomRsrcCtx, domainName, "role2", auditRef, role2);

        Role role3 = createRoleObject(domainName, "role3", null,
                "user.jack", "user.jack.sub1.api");
        zms.putRole(mockDomRsrcCtx, domainName, "role3", auditRef, role3);
        
        UserList userList = zms.getUserList(mockDomRsrcCtx);
        List<String> users = userList.getNames();
        int userSize = users.size();
        assertTrue(users.contains("user.testadminuser"));
        assertTrue(users.contains("user.jack"));
        assertTrue(users.contains("user.joe"));
        
        zms.deleteUser(mockDomRsrcCtx, "jack", auditRef);
        
        userList = zms.getUserList(mockDomRsrcCtx);
        users = userList.getNames();
        assertEquals(users.size(), userSize - 1);
        assertTrue(users.contains("user.testadminuser"));
        assertTrue(users.contains("user.joe"));
        assertFalse(users.contains("user.jack"));

        try {
            zms.getDomain(mockDomRsrcCtx, "user.jack");
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), ResourceException.NOT_FOUND);
        }
        
        try {
            zms.getDomain(mockDomRsrcCtx, "user.jack.sub1");
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), ResourceException.NOT_FOUND);
        }
        
        zms.deleteTopLevelDomain(mockDomRsrcCtx, domainName, auditRef);
        zms.deleteTopLevelDomain(mockDomRsrcCtx, "deleteusersports", auditRef);
    }

    @Test
    public void testGetDomainRoleMembersInvalidDomain() {

        try {
            zms.getDomainRoleMembers(mockDomRsrcCtx, "invalid-domain");
            fail();
        } catch (ResourceException ex) {
            assertEquals(404, ex.getCode());
        }
    }

    @Test
    public void testDeleteDomainRoleMemberInvalidDomain() {

        try {
            zms.deleteDomainRoleMember(mockDomRsrcCtx, "invalid-domain", "user.joe", auditRef);
            fail();
        } catch (ResourceException ex) {
            assertEquals(404, ex.getCode());
        }
    }

    @Test
    public void testDeleteDomainRoleMember() {

        String domainName = "deletedomainrolemember2";

        TopLevelDomain dom1 = createTopLevelDomainObject(domainName,
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        Role role1 = createRoleObject(domainName, "role1", null,
                "user.jack", "user.janie");
        zms.putRole(mockDomRsrcCtx, domainName, "role1", auditRef, role1);

        Role role2 = createRoleObject(domainName, "role2", null,
                "user.janie", "user.jane");
        zms.putRole(mockDomRsrcCtx, domainName, "role2", auditRef, role2);

        Role role3 = createRoleObject(domainName, "role3", null,
                "user.jack", "user.jane");
        zms.putRole(mockDomRsrcCtx, domainName, "role3", auditRef, role3);

        Role role4 = createRoleObject(domainName, "role4", null,
                "user.jack", null);
        zms.putRole(mockDomRsrcCtx, domainName, "role4", auditRef, role4);

        Role role5 = createRoleObject(domainName, "role5", null,
                "user.jack-service", "user.jane");
        zms.putRole(mockDomRsrcCtx, domainName, "role5", auditRef, role5);

        DomainRoleMembers domainRoleMembers = zms.getDomainRoleMembers(mockDomRsrcCtx, domainName);
        assertEquals(domainName, domainRoleMembers.getDomainName());

        List<DomainRoleMember> members = domainRoleMembers.getMembers();
        assertNotNull(members);
        assertEquals(5, members.size());
        ZMSTestUtils.verifyDomainRoleMember(members, "user.jack", "role1", "role3", "role4");
        ZMSTestUtils.verifyDomainRoleMember(members, "user.janie", "role1", "role2");
        ZMSTestUtils.verifyDomainRoleMember(members, "user.jane", "role2", "role3", "role5");
        ZMSTestUtils.verifyDomainRoleMember(members, "user.jack-service", "role5");
        ZMSTestUtils.verifyDomainRoleMember(members, adminUser, "admin");

        // with unknown user we get back 404

        try {
            zms.deleteDomainRoleMember(mockDomRsrcCtx, domainName, "user.unknown", auditRef);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), ResourceException.NOT_FOUND);
        }

        members = domainRoleMembers.getMembers();
        assertNotNull(members);
        assertEquals(5, members.size());
        ZMSTestUtils.verifyDomainRoleMember(members, "user.jack", "role1", "role3", "role4");
        ZMSTestUtils.verifyDomainRoleMember(members, "user.janie", "role1", "role2");
        ZMSTestUtils.verifyDomainRoleMember(members, "user.jane", "role2", "role3", "role5");
        ZMSTestUtils.verifyDomainRoleMember(members, "user.jack-service", "role5");
        ZMSTestUtils.verifyDomainRoleMember(members, adminUser, "admin");

        // now remove a known user

        zms.deleteDomainRoleMember(mockDomRsrcCtx, domainName, "user.jack", auditRef);

        domainRoleMembers = zms.getDomainRoleMembers(mockDomRsrcCtx, domainName);
        assertEquals(domainName, domainRoleMembers.getDomainName());

        members = domainRoleMembers.getMembers();
        assertNotNull(members);
        assertEquals(4, members.size());
        ZMSTestUtils.verifyDomainRoleMember(members, "user.janie", "role1", "role2");
        ZMSTestUtils.verifyDomainRoleMember(members, "user.jane", "role2", "role3", "role5");
        ZMSTestUtils.verifyDomainRoleMember(members, "user.jack-service", "role5");
        ZMSTestUtils.verifyDomainRoleMember(members, adminUser, "admin");

        zms.deleteTopLevelDomain(mockDomRsrcCtx, domainName, auditRef);
    }

    @Test
    public void testPutQuota() {

        String domainName = "putquota";
        
        TopLevelDomain dom1 = createTopLevelDomainObject(domainName,
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        Quota quota = new Quota().setName(domainName)
                .setAssertion(10).setEntity(11)
                .setPolicy(12).setPublicKey(13)
                .setRole(14).setRoleMember(15)
                .setService(16).setServiceHost(17)
                .setSubdomain(18).setGroupMember(19).setGroup(20);
        
        zms.putQuota(mockDomRsrcCtx, domainName, auditRef, quota);

        // now retrieve the quota using zms interface
        
        Quota quotaCheck = zms.getQuota(mockDomRsrcCtx, domainName);
        assertNotNull(quotaCheck);
        assertEquals(quotaCheck.getAssertion(), 10);
        assertEquals(quotaCheck.getRole(), 14);
        assertEquals(quotaCheck.getPolicy(), 12);
        
        zms.deleteTopLevelDomain(mockDomRsrcCtx, domainName, auditRef);
    }
    
    @Test
    public void testPutQuotaMismatchName() {

        String domainName = "putquotamismatchname";
        
        TopLevelDomain dom1 = createTopLevelDomainObject(domainName,
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        Quota quota = new Quota().setName("athenz")
                .setAssertion(10).setEntity(11)
                .setPolicy(12).setPublicKey(13)
                .setRole(14).setRoleMember(15)
                .setService(16).setServiceHost(17)
                .setSubdomain(18);
        
        try {
            zms.putQuota(mockDomRsrcCtx, domainName, auditRef, quota);
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), ResourceException.BAD_REQUEST);
        }
        
        zms.deleteTopLevelDomain(mockDomRsrcCtx, domainName, auditRef);
    }
    
    @Test
    public void testDeleteQuota() {

        String domainName = "deletequota";
        
        TopLevelDomain dom1 = createTopLevelDomainObject(domainName,
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        Quota quota = new Quota().setName(domainName)
                .setAssertion(10).setEntity(11)
                .setPolicy(12).setPublicKey(13)
                .setRole(14).setRoleMember(15)
                .setService(16).setServiceHost(17)
                .setSubdomain(18).setGroupMember(19).setGroup(20);
        
        zms.putQuota(mockDomRsrcCtx, domainName, auditRef, quota);

        Quota quotaCheck = zms.getQuota(mockDomRsrcCtx, domainName);
        assertNotNull(quotaCheck);
        assertEquals(domainName, quotaCheck.getName());
        assertEquals(quotaCheck.getAssertion(), 10);
        assertEquals(quotaCheck.getRole(), 14);
        assertEquals(quotaCheck.getPolicy(), 12);
        
        // now delete the quota
        
        zms.deleteQuota(mockDomRsrcCtx, domainName, auditRef);
        
        // now we'll get the default quota
        
        quotaCheck = zms.getQuota(mockDomRsrcCtx, domainName);

        assertEquals("server-default", quotaCheck.getName());
        assertEquals(quotaCheck.getAssertion(), 100);
        assertEquals(quotaCheck.getRole(), 1000);
        assertEquals(quotaCheck.getPolicy(), 1000);
        
        zms.deleteTopLevelDomain(mockDomRsrcCtx, domainName, auditRef);
    }
    
    @Test
    public void testUserHomeDomainResource() {
        ZMSImpl zmsImpl = zmsInit();
        
        PrincipalAuthority principalAuthority = new com.yahoo.athenz.auth.impl.PrincipalAuthority();
        PrincipalAuthority testPrincipalAuthority = new com.yahoo.athenz.zms.TestUserPrincipalAuthority();
        
        // no changes expected
        
        zmsImpl.userDomain = "user";
        zmsImpl.userDomainPrefix = "user.";
        zmsImpl.homeDomain = "user";
        zmsImpl.homeDomainPrefix = "user.";
        zmsImpl.userAuthority = principalAuthority;
        assertEquals(zmsImpl.userHomeDomainResource("user.hga:domain"), "user.hga:domain");
        assertEquals(zmsImpl.userHomeDomainResource("user.john.smith:domain"), "user.john.smith:domain");
        assertEquals(zmsImpl.userHomeDomainResource("testuser.john.smith:domain"), "testuser.john.smith:domain");
        assertEquals(zmsImpl.userHomeDomainResource("product.john.smith:domain"), "product.john.smith:domain");

        // no changes expected
        
        zmsImpl.userDomain = "user";
        zmsImpl.userDomainPrefix = "user.";
        zmsImpl.homeDomain = "user";
        zmsImpl.homeDomainPrefix = "user.";
        zmsImpl.userAuthority = testPrincipalAuthority;
        assertEquals(zmsImpl.userHomeDomainResource("user.hga:domain"), "user.hga:domain");
        assertEquals(zmsImpl.userHomeDomainResource("user.john.smith:domain"), "user.john.smith:domain");
        assertEquals(zmsImpl.userHomeDomainResource("testuser.john.smith:domain"), "testuser.john.smith:domain");
        assertEquals(zmsImpl.userHomeDomainResource("product.john.smith:domain"), "product.john.smith:domain");
        
        // only domain name is changed - no username changes since user/home are same
        
        zmsImpl.userDomain = "testuser";
        zmsImpl.userDomainPrefix = "testuser.";
        zmsImpl.homeDomain = "testuser";
        zmsImpl.homeDomainPrefix = "testuser.";
        zmsImpl.userAuthority = principalAuthority;
        assertEquals(zmsImpl.userHomeDomainResource("user.hga:domain"), "testuser.hga:domain");
        assertEquals(zmsImpl.userHomeDomainResource("user.john.smith:domain"), "testuser.john.smith:domain");
        assertEquals(zmsImpl.userHomeDomainResource("testuser.john.smith:domain"), "testuser.john.smith:domain");
        assertEquals(zmsImpl.userHomeDomainResource("product.john.smith:domain"), "product.john.smith:domain");
        
        // only domain name is changed - no username changes since user/home are same

        zmsImpl.userDomain = "testuser";
        zmsImpl.userDomainPrefix = "testuser.";
        zmsImpl.homeDomain = "testuser";
        zmsImpl.homeDomainPrefix = "testuser.";
        zmsImpl.userAuthority = testPrincipalAuthority;
        assertEquals(zmsImpl.userHomeDomainResource("user.hga:domain"), "testuser.hga:domain");
        assertEquals(zmsImpl.userHomeDomainResource("user.john.smith:domain"), "testuser.john.smith:domain");
        assertEquals(zmsImpl.userHomeDomainResource("testuser.john.smith:domain"), "testuser.john.smith:domain");
        assertEquals(zmsImpl.userHomeDomainResource("product.john.smith:domain"), "product.john.smith:domain");
        
        // domain and username are changed since user/home namespaces are different
        // username impl in authority is default so we'll end up with same username
        
        zmsImpl.userDomain = "user";
        zmsImpl.userDomainPrefix = "user.";
        zmsImpl.homeDomain = "home";
        zmsImpl.homeDomainPrefix = "home.";
        zmsImpl.userAuthority = principalAuthority;
        assertEquals(zmsImpl.userHomeDomainResource("user.hga:domain"), "home.hga:domain");
        assertEquals(zmsImpl.userHomeDomainResource("user.john.smith:domain"), "home.john.smith:domain");
        assertEquals(zmsImpl.userHomeDomainResource("testuser.john.smith:domain"), "testuser.john.smith:domain");
        assertEquals(zmsImpl.userHomeDomainResource("product.john.smith:domain"), "product.john.smith:domain");
        
        // domain and username are changed since user/home namespaces are different
        // username impl in authority will replace .'s with -'s

        zmsImpl.userDomain = "user";
        zmsImpl.userDomainPrefix = "user.";
        zmsImpl.homeDomain = "home";
        zmsImpl.homeDomainPrefix = "home.";
        zmsImpl.userAuthority = testPrincipalAuthority;
        assertEquals(zmsImpl.userHomeDomainResource("user.hga:domain"), "home.hga:domain");
        assertEquals(zmsImpl.userHomeDomainResource("user.john.smith:domain"), "home.john-smith:domain");
        assertEquals(zmsImpl.userHomeDomainResource("testuser.john.smith:domain"), "testuser.john.smith:domain");
        assertEquals(zmsImpl.userHomeDomainResource("product.john.smith:domain"), "product.john.smith:domain");
        
        // domain and username are changed since user/home namespaces are different
        // username impl in authority is default so we'll end up with same username
        
        zmsImpl.userDomain = "testuser";
        zmsImpl.userDomainPrefix = "testuser.";
        zmsImpl.homeDomain = "home";
        zmsImpl.homeDomainPrefix = "home.";
        zmsImpl.userAuthority = principalAuthority;
        assertEquals(zmsImpl.userHomeDomainResource("user.hga:domain"), "home.hga:domain");
        assertEquals(zmsImpl.userHomeDomainResource("user.john.smith:domain"), "home.john.smith:domain");
        assertEquals(zmsImpl.userHomeDomainResource("testuser.john.smith:domain"), "testuser.john.smith:domain");
        assertEquals(zmsImpl.userHomeDomainResource("product.john.smith:domain"), "product.john.smith:domain");
        
        // domain and username are changed since user/home namespaces are different
        // username impl in authority will replace .'s with -'s
        
        zmsImpl.userDomain = "testuser";
        zmsImpl.userDomainPrefix = "testuser.";
        zmsImpl.homeDomain = "home";
        zmsImpl.homeDomainPrefix = "home.";
        zmsImpl.userAuthority = testPrincipalAuthority;
        assertEquals(zmsImpl.userHomeDomainResource("user.hga:domain"), "home.hga:domain");
        assertEquals(zmsImpl.userHomeDomainResource("user.john.smith:domain"), "home.john-smith:domain");
        assertEquals(zmsImpl.userHomeDomainResource("testuser.john.smith:domain"), "testuser.john.smith:domain");
        assertEquals(zmsImpl.userHomeDomainResource("product.john.smith:domain"), "product.john.smith:domain");

        zmsImpl.objectStore.clearConnections();
    }

    @Test
    public void testCreatePrincipalForName() {
        
        ZMSImpl zmsImpl = zmsInit();
        zmsImpl.userDomain = "user";
        zmsImpl.userDomainAlias = null;
        
        Principal principal = zmsImpl.createPrincipalForName("joe");
        assertEquals(principal.getFullName(), "user.joe");
        
        principal = zmsImpl.createPrincipalForName("joe-smith");
        assertEquals(principal.getFullName(), "user.joe-smith");
        
        principal = zmsImpl.createPrincipalForName("user.joe");
        assertEquals(principal.getFullName(), "user.joe");

        principal = zmsImpl.createPrincipalForName("user.joe.storage");
        assertEquals(principal.getFullName(), "user.joe.storage");
        
        principal = zmsImpl.createPrincipalForName("alias.joe");
        assertEquals(principal.getFullName(), "alias.joe");
        
        principal = zmsImpl.createPrincipalForName("alias.joe.storage");
        assertEquals(principal.getFullName(), "alias.joe.storage");
        
        zmsImpl.userDomainAlias = "alias";
        
        principal = zmsImpl.createPrincipalForName("joe");
        assertEquals(principal.getFullName(), "user.joe");
        
        principal = zmsImpl.createPrincipalForName("joe-smith");
        assertEquals(principal.getFullName(), "user.joe-smith");
        
        principal = zmsImpl.createPrincipalForName("user.joe");
        assertEquals(principal.getFullName(), "user.joe");

        principal = zmsImpl.createPrincipalForName("user.joe.storage");
        assertEquals(principal.getFullName(), "user.joe.storage");
        
        principal = zmsImpl.createPrincipalForName("alias.joe");
        assertEquals(principal.getFullName(), "user.joe");
        
        principal = zmsImpl.createPrincipalForName("alias.joe.storage");
        assertEquals(principal.getFullName(), "alias.joe.storage");

        zmsImpl.objectStore.clearConnections();
    }
    
    @Test
    public void testNormalizedAdminUsers() {
        List<String> list = new ArrayList<>();
        list.add("user-alias.user1");
        list.add("user-alias.user1.svc");
        list.add("user.user2");
        list.add("user.user2.svc");
        
        ZMSImpl zmsImpl = zmsInit();
        zmsImpl.userDomain = "user";
        zmsImpl.userDomainPrefix = "user.";
        zmsImpl.userDomainAlias = null;
        zmsImpl.userDomainAliasPrefix = null;
        
        List<String> normList = zmsImpl.normalizedAdminUsers(list, null, "unit-test");
        assertEquals(normList.size(), 4);
        assertTrue(normList.contains("user-alias.user1"));
        assertTrue(normList.contains("user-alias.user1.svc"));
        assertTrue(normList.contains("user.user2"));
        assertTrue(normList.contains("user.user2.svc"));
        
        zmsImpl.userDomainAlias = "user-alias";
        zmsImpl.userDomainAliasPrefix = "user-alias.";
        
        normList = zmsImpl.normalizedAdminUsers(list, null, "unit-test");
        assertEquals(normList.size(), 4);
        assertTrue(normList.contains("user.user1"));
        assertTrue(normList.contains("user-alias.user1.svc"));
        assertTrue(normList.contains("user.user2"));
        assertTrue(normList.contains("user.user2.svc"));

        zmsImpl.objectStore.clearConnections();
    }
    
    @Test
    public void testNormalizeDomainAliasUser() {

        ZMSImpl zmsImpl = zmsInit();
        zmsImpl.userDomain = "user";
        zmsImpl.userDomainPrefix = "user.";
        zmsImpl.userDomainAlias = null;
        zmsImpl.userDomainAliasPrefix = null;
        
        assertNull(zmsImpl.normalizeDomainAliasUser(null));
        assertEquals(zmsImpl.normalizeDomainAliasUser("user-alias.user1"), "user-alias.user1");
        assertEquals(zmsImpl.normalizeDomainAliasUser("user-alias.user1.svc"), "user-alias.user1.svc");
        assertEquals(zmsImpl.normalizeDomainAliasUser("user.user2"), "user.user2");
        assertEquals(zmsImpl.normalizeDomainAliasUser("user.user2.svc"), "user.user2.svc");
        
        zmsImpl.userDomainAlias = "user-alias";
        zmsImpl.userDomainAliasPrefix = "user-alias.";
        assertNull(zmsImpl.normalizeDomainAliasUser(null));
        assertEquals(zmsImpl.normalizeDomainAliasUser("user-alias.user1"), "user.user1");
        assertEquals(zmsImpl.normalizeDomainAliasUser("user-alias.user1.svc"), "user-alias.user1.svc");
        assertEquals(zmsImpl.normalizeDomainAliasUser("user.user2"), "user.user2");
        assertEquals(zmsImpl.normalizeDomainAliasUser("user.user2.svc"), "user.user2.svc");

        zmsImpl.objectStore.clearConnections();
    }
    
    @Test
    public void testValidateRequestSecureRequests() {
        ZMSImpl zmsImpl = zmsInit();
        zmsImpl.secureRequestsOnly = false;
        zmsImpl.statusPort = 0;
        
        HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
        Mockito.when(request.isSecure()).thenReturn(true);
        
        // if secure requests is false, no check is done
        
        zmsImpl.validateRequest(request, "test");
        zmsImpl.validateRequest(request, "test", false);
        zmsImpl.validateRequest(request, "test", true);
        
        // should complete successfully since our request is true
        
        zmsImpl.secureRequestsOnly = true;
        zmsImpl.validateRequest(request, "test");
        zmsImpl.validateRequest(request, "test", false);
        zmsImpl.validateRequest(request, "test", true);

        zmsImpl.objectStore.clearConnections();
    }
    
    @Test
    public void testValidateRequestNonSecureRequests() {
        ZMSImpl zmsImpl = zmsInit();
        zmsImpl.secureRequestsOnly = true;
        zmsImpl.statusPort = 0;
        
        HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
        
        // if request is not secure, should be rejected
        
        Mockito.when(request.isSecure()).thenReturn(false);
        try {
            zmsImpl.validateRequest(request, "test");
            fail();
        } catch (ResourceException ignored) {
        }
        try {
            zmsImpl.validateRequest(request, "test", false);
            fail();
        } catch (ResourceException ignored) {
        }
        try {
            zmsImpl.validateRequest(request, "test", true);
            fail();
        } catch (ResourceException ignored) {
        }

        zmsImpl.objectStore.clearConnections();
    }
    
    @Test
    public void testValidateRequestStatusRequestPort() {
        
        ZMSImpl zmsImpl = zmsInit();
        zmsImpl.secureRequestsOnly = true;
        zmsImpl.statusPort = 8443;
        
        HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
        Mockito.when(request.isSecure()).thenReturn(true);
        Mockito.when(request.getLocalPort()).thenReturn(4443);
        
        // non-status requests are allowed on port 4443
        
        zmsImpl.validateRequest(request, "test");
        zmsImpl.validateRequest(request, "test", false);

        // status requests are not allowed on port 4443
        
        try {
            zmsImpl.validateRequest(request, "test", true);
            fail();
        } catch (ResourceException ignored) {
        }

        zmsImpl.objectStore.clearConnections();
    }
    
    @Test
    public void testValidateRequestRegularRequestPort() {
        
        ZMSImpl zmsImpl = zmsInit();
        zmsImpl.secureRequestsOnly = true;
        zmsImpl.statusPort = 8443;
        
        HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
        Mockito.when(request.isSecure()).thenReturn(true);
        Mockito.when(request.getLocalPort()).thenReturn(8443);
        
        // status requests are allowed on port 8443
        
        zmsImpl.validateRequest(request, "test", true);

        // non-status requests are not allowed on port 8443
        
        try {
            zmsImpl.validateRequest(request, "test");
            fail();
        } catch (ResourceException ignored) {
        }
        
        try {
            zmsImpl.validateRequest(request, "test", false);
            fail();
        } catch (ResourceException ignored) {
        }

        zmsImpl.objectStore.clearConnections();
    }
    
    @Test
    public void testGetStatus() {

        ZMSImpl zmsImpl = zmsInit();
        zmsImpl.statusPort = 0;
        Status status = zmsImpl.getStatus(mockDomRsrcCtx);
        assertEquals(status.getCode(), ResourceException.OK);

        zmsImpl.objectStore.clearConnections();
    }

    @Test
    public void testGetStatusWithStatusFile() throws IOException {

        System.setProperty(ZMSConsts.ZMS_PROP_HEALTH_CHECK_PATH, "/tmp/zms-healthcheck");
        ZMSImpl zmsImpl = zmsInit();
        zmsImpl.statusPort = 0;

        // without the file we should get failure - make sure
        // to delete it just in case left over from previous run

        File healthCheckFile = new File("/tmp/zms-healthcheck");
        healthCheckFile.delete();

        try {
            zmsImpl.getStatus(mockDomRsrcCtx);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ResourceException.NOT_FOUND, ex.getCode());
        }

        // create the status file

        new FileOutputStream(healthCheckFile).close();
        Status status = zmsImpl.getStatus(mockDomRsrcCtx);
        assertEquals(ResourceException.OK, status.getCode());

        // delete the status file

        healthCheckFile.delete();
        try {
            zmsImpl.getStatus(mockDomRsrcCtx);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ResourceException.NOT_FOUND, ex.getCode());
        }

        System.clearProperty(ZMSConsts.ZMS_PROP_HEALTH_CHECK_PATH);
        zmsImpl.objectStore.clearConnections();
    }

    @Test
    public void testGetStatusWithStatusChecker() {

        // if the MockStatusCheckerNoException is set
        // the MockStatusCheckerNoException determines the server is healthy

        System.setProperty(ZMSConsts.ZMS_PROP_STATUS_CHECKER_FACTORY_CLASS,
                MockStatusCheckerNoException.class.getName());
        ZMSImpl zmsImpl = zmsInit();
        zmsImpl.statusPort = 0;

        Status status = zmsImpl.getStatus(mockDomRsrcCtx);
        assertEquals(ResourceException.OK, status.getCode());

        // if the MockStatusCheckerThrowException is set
        // the MockStatusCheckerThrowException determines that there is a problem with the server

        System.setProperty(ZMSConsts.ZMS_PROP_STATUS_CHECKER_FACTORY_CLASS,
                MockStatusCheckerThrowException.NoArguments.class.getName());
        zmsImpl = zmsInit();
        zmsImpl.statusPort = 0;

        try {
            zmsImpl.getStatus(mockDomRsrcCtx);
            fail();
        } catch (ResourceException ex) {
            int code = com.yahoo.athenz.common.server.rest.ResourceException.INTERNAL_SERVER_ERROR;
            String msg = com.yahoo.athenz.common.server.rest.ResourceException.symbolForCode(ResourceException.INTERNAL_SERVER_ERROR);
            assertEquals(new ResourceError().code(code).message(msg).toString(), ex.getData().toString());
        }

        System.setProperty(ZMSConsts.ZMS_PROP_STATUS_CHECKER_FACTORY_CLASS,
                MockStatusCheckerThrowException.NotFound.class.getName());
        zmsImpl = zmsInit();
        zmsImpl.statusPort = 0;

        try {
            zmsImpl.getStatus(mockDomRsrcCtx);
            fail();
        } catch (ResourceException ex) {
            int code = com.yahoo.athenz.common.server.rest.ResourceException.NOT_FOUND;
            String msg = com.yahoo.athenz.common.server.rest.ResourceException.symbolForCode(ResourceException.NOT_FOUND);
            assertEquals(new ResourceError().code(code).message(msg).toString(), ex.getData().toString());
        }

        System.setProperty(ZMSConsts.ZMS_PROP_STATUS_CHECKER_FACTORY_CLASS,
                MockStatusCheckerThrowException.InternalServerErrorWithMessage.class.getName());
        zmsImpl = zmsInit();
        zmsImpl.statusPort = 0;

        try {
            zmsImpl.getStatus(mockDomRsrcCtx);
            fail();
        } catch (ResourceException ex) {
            int code = com.yahoo.athenz.common.server.rest.ResourceException.INTERNAL_SERVER_ERROR;
            String msg = "error message";
            assertEquals(new ResourceError().code(code).message(msg).toString(), ex.getData().toString());
        }

        System.setProperty(ZMSConsts.ZMS_PROP_STATUS_CHECKER_FACTORY_CLASS,
                MockStatusCheckerThrowException.CauseRuntimeException.class.getName());
        zmsImpl = zmsInit();
        zmsImpl.statusPort = 0;

        try {
            zmsImpl.getStatus(mockDomRsrcCtx);
            fail();
        } catch (ResourceException ex) {
            int code = com.yahoo.athenz.common.server.rest.ResourceException.INTERNAL_SERVER_ERROR;
            String msg = "runtime exception";
            assertEquals(new ResourceError().code(code).message(msg).toString(), ex.getData().toString());
        }

        System.clearProperty(ZMSConsts.ZMS_PROP_STATUS_CHECKER_FACTORY_CLASS);
        zmsImpl.objectStore.clearConnections();
    }

    @Test
    public void testValidateString() {
        ZMSImpl zmsImpl = zmsInit();
        zmsImpl.validateString(null, "CompoundName", "unit-test");
        zmsImpl.validateString("", "CompoundName", "unit-test");
        zmsImpl.validateString("124356789012", "CompoundName", "unit-test");
        zmsImpl.validateString("unit-test_test-101", "CompoundName", "unit-test");
        try {
            zmsImpl.validateString("unit test", "CompoundName", "unit-test");
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 400);
        }
        zmsImpl.objectStore.clearConnections();
    }

    @Test
    public void testgetModTimestampEmtpy() {
        ZMSImpl zmsImpl = zmsInit();
        assertEquals(zmsImpl.getModTimestamp(null), 0);
        assertEquals(zmsImpl.getModTimestamp("\"\""), 0);
        assertEquals(zmsImpl.getModTimestamp(""), 0);
        zmsImpl.objectStore.clearConnections();
    }

    @Test
    public void testValidCORSOrigin() {

        ZMSImpl zmsImpl = zmsInit();

        // invalid origin values tests

        assertFalse(zmsImpl.isValidCORSOrigin(null));
        assertFalse(zmsImpl.isValidCORSOrigin(""));

        // origin white list not configured tests

        zmsImpl.corsOriginList = null;
        assertTrue(zmsImpl.isValidCORSOrigin("http://cors.origin1"));
        assertTrue(zmsImpl.isValidCORSOrigin("http://cors.origin2"));

        zmsImpl.corsOriginList = new HashSet<>();
        assertTrue(zmsImpl.isValidCORSOrigin("http://cors.origin1"));
        assertTrue(zmsImpl.isValidCORSOrigin("http://cors.origin2"));

        // origin white list configured tests

        zmsImpl.corsOriginList.add("http://cors.origin1");
        assertTrue(zmsImpl.isValidCORSOrigin("http://cors.origin1"));
        assertFalse(zmsImpl.isValidCORSOrigin("http://cors.origin2"));

        zmsImpl.objectStore.clearConnections();
    }

    @Test
    public void testValidateServiceName() {

        ZMSImpl zmsImpl = zmsInit();

        // reserved names
        assertFalse(zmsImpl.isValidServiceName("com"));
        assertFalse(zmsImpl.isValidServiceName("gov"));
        assertFalse(zmsImpl.isValidServiceName("info"));
        assertFalse(zmsImpl.isValidServiceName("org"));

        assertTrue(zmsImpl.isValidServiceName("svc"));
        assertTrue(zmsImpl.isValidServiceName("acom"));
        assertTrue(zmsImpl.isValidServiceName("coms"));
        assertTrue(zmsImpl.isValidServiceName("borg"));

        // service names with 1 or 2 chars

        assertFalse(zmsImpl.isValidServiceName("u"));
        assertFalse(zmsImpl.isValidServiceName("k"));
        assertFalse(zmsImpl.isValidServiceName("r"));

        assertFalse(zmsImpl.isValidServiceName("us"));
        assertFalse(zmsImpl.isValidServiceName("uk"));
        assertFalse(zmsImpl.isValidServiceName("fr"));

        // set the min length to 0 and verify all pass

        zmsImpl.serviceNameMinLength = 0;
        assertTrue(zmsImpl.isValidServiceName("r"));
        assertTrue(zmsImpl.isValidServiceName("us"));
        assertTrue(zmsImpl.isValidServiceName("svc"));

        // set map to null and verify all pass

        zmsImpl.reservedServiceNames = null;
        assertTrue(zmsImpl.isValidServiceName("com"));
        assertTrue(zmsImpl.isValidServiceName("gov"));

        // create new impl objects with new settings

        System.setProperty(ZMSConsts.ZMS_PROP_RESERVED_SERVICE_NAMES, "one,two");
        System.setProperty(ZMSConsts.ZMS_PROP_SERVICE_NAME_MIN_LENGTH, "0");
        ZMSImpl zmsImpl2 = zmsInit();

        assertTrue(zmsImpl2.isValidServiceName("com"));
        assertTrue(zmsImpl2.isValidServiceName("gov"));
        assertTrue(zmsImpl2.isValidServiceName("info"));

        assertFalse(zmsImpl2.isValidServiceName("one"));
        assertFalse(zmsImpl2.isValidServiceName("two"));

        assertTrue(zmsImpl2.isValidServiceName("u"));
        assertTrue(zmsImpl2.isValidServiceName("k"));
        assertTrue(zmsImpl2.isValidServiceName("r"));
        System.clearProperty(ZMSConsts.ZMS_PROP_RESERVED_SERVICE_NAMES);
        System.clearProperty(ZMSConsts.ZMS_PROP_SERVICE_NAME_MIN_LENGTH);

        zmsImpl.objectStore.clearConnections();
    }

    @Test
    public void testRetrieveSignedDomainMeta() {

        ZMSImpl zmsImpl = zmsInit();
        Domain domainMeta = new Domain().setName("dom1").setYpmId(123).setModified(Timestamp.fromCurrentTime())
                .setAccount("1234").setAuditEnabled(true).setOrg("org");
        SignedDomain domain = zmsImpl.retrieveSignedDomainMeta(domainMeta, null);
        assertNull(domain.getDomain().getAccount());
        assertNull(domain.getDomain().getYpmId());

        domain = zmsImpl.retrieveSignedDomainMeta(domainMeta, "unknown");
        assertNull(domain.getDomain().getAccount());
        assertNull(domain.getDomain().getYpmId());
        assertNull(domain.getDomain().getOrg());
        assertNull(domain.getDomain().getAuditEnabled());

        domain = zmsImpl.retrieveSignedDomainMeta(domainMeta, "account");
        assertEquals(domain.getDomain().getAccount(), "1234");
        assertNull(domain.getDomain().getYpmId());
        assertNull(domain.getDomain().getOrg());
        assertNull(domain.getDomain().getAuditEnabled());

        domain = zmsImpl.retrieveSignedDomainMeta(domainMeta, "ypmid");
        assertNull(domain.getDomain().getAccount());
        assertEquals(domain.getDomain().getYpmId().intValue(), 123);
        assertNull(domain.getDomain().getOrg());
        assertNull(domain.getDomain().getAuditEnabled());

        domain = zmsImpl.retrieveSignedDomainMeta(domainMeta, "all");
        assertEquals(domain.getDomain().getAccount(), "1234");
        assertEquals(domain.getDomain().getYpmId().intValue(), 123);
        assertEquals(domain.getDomain().getOrg(), "org");
        assertTrue(domain.getDomain().getAuditEnabled());

        domainMeta.setAccount(null);
        domain = zmsImpl.retrieveSignedDomainMeta(domainMeta, "account");
        assertNull(domain);

        domainMeta.setAccount("1234");
        domainMeta.setYpmId(null);
        domain = zmsImpl.retrieveSignedDomainMeta(domainMeta, "ypmid");
        assertNull(domain);

        zmsImpl.objectStore.clearConnections();
    }

    @Test
    public void testRetrieveSignedDomainDataNotFound() {

        ZMSImpl zmsImpl = zmsInit();
        SignedDomain domain = zmsImpl.retrieveSignedDomainData("unknown", 1234, true);
        assertNull(domain);

        // now signed domains with unknown domain name

        Authority principalAuthority = new com.yahoo.athenz.common.server.debug.DebugPrincipalAuthority();
        Principal sysPrincipal = principalAuthority.authenticate("v=U1;d=sys;n=zts;s=signature",
                "10.11.12.13", "GET", null);
        ResourceContext rsrcCtx = createResourceContext(sysPrincipal);

        Response response = zms.getSignedDomains(rsrcCtx, "unknown", null, null, Boolean.TRUE, null);
        SignedDomains sdoms = (SignedDomains) response.getEntity();

        assertNotNull(sdoms);
        List<SignedDomain> list = sdoms.getDomains();
        assertEquals(0, list.size());

        zmsImpl.objectStore.clearConnections();
    }

    @Test
    public void testGetSignedDomainsWithMetaAttrs() {

        // create multiple top level domains
        TopLevelDomain dom1 = createTopLevelDomainObject("SignedDom1",
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        // set the meta attributes for domain

        DomainMeta meta = createDomainMetaObject("Tenant Domain1", null, true, false, "12345", 0);
        zms.putDomainMeta(mockDomRsrcCtx, "signeddom1", auditRef, meta);
        meta = createDomainMetaObject("Tenant Domain1", null, true, false, "12345", 987654103);
        zms.putDomainSystemMeta(mockDomRsrcCtx, "signeddom1", "account", auditRef, meta);
        zms.putDomainSystemMeta(mockDomRsrcCtx, "signeddom1", "productid", auditRef, meta);

        TopLevelDomain dom2 = createTopLevelDomainObject("SignedDom2",
                "Test Domain2", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom2);

        meta = createDomainMetaObject("Tenant Domain2", null, true, false, "12346", null);
        zms.putDomainMeta(mockDomRsrcCtx, "signeddom2", auditRef, meta);
        meta = createDomainMetaObject("Tenant Domain2", null, true, false, "12346", null);
        zms.putDomainSystemMeta(mockDomRsrcCtx, "signeddom2", "account", auditRef, meta);

        setupPrincipalSystemMetaDelete(zms, mockDomRsrcCtx.principal().getFullName(), "signeddom2", "productid");
        meta = createDomainMetaObject("Tenant Domain2", null, true, false, "12346", null);
        zms.putDomainSystemMeta(mockDomRsrcCtx, "signeddom2", "productid", auditRef, meta);
        cleanupPrincipalSystemMetaDelete(zms);

        DomainList domList = zms.getDomainList(mockDomRsrcCtx, null, null, null, null,
                null, null, null, null, null);
        assertNotNull(domList);

        zms.privateKey = new ServerPrivateKey(Crypto.loadPrivateKey(Crypto.ybase64DecodeString(privKey)), "0");

        Authority principalAuthority = new com.yahoo.athenz.common.server.debug.DebugPrincipalAuthority();
        Principal sysPrincipal = principalAuthority.authenticate("v=U1;d=sys;n=zts;s=signature",
                "10.11.12.13", "GET", null);
        ResourceContext rsrcCtx = createResourceContext(sysPrincipal);

        // we're going to ask for entries with ypm id so we'll only
        // get one of the domains back - dom1 but not dom2

        Response response = zms.getSignedDomains(rsrcCtx, null, "true", "ypmid", Boolean.TRUE, null);
        SignedDomains sdoms = (SignedDomains) response.getEntity();
        assertNotNull(sdoms);
        List<SignedDomain> list = sdoms.getDomains();
        assertNotNull(list);

        boolean dom1Found = false;
        boolean dom2Found = false;
        for (SignedDomain sDomain : list) {
            DomainData domainData = sDomain.getDomain();
            switch (domainData.getName()) {
                case "signeddom1":
                    dom1Found = true;
                    break;
                case "signeddom2":
                    dom2Found = true;
                    break;
            }
        }
        assertTrue(dom1Found);
        assertFalse(dom2Found);

        // now asking for specific domains with ypm id
        // first signeddom1 with should return

        response = zms.getSignedDomains(rsrcCtx, "signeddom1", "true", "ypmid", Boolean.TRUE, null);
        sdoms = (SignedDomains) response.getEntity();

        assertNotNull(sdoms);
        list = sdoms.getDomains();
        assertNotNull(list);
        assertEquals(list.size(), 1);

        DomainData domainData = list.get(0).getDomain();
        assertEquals(domainData.getName(), "signeddom1");

        // then signeddom2 with should not return

        response = zms.getSignedDomains(rsrcCtx, "signeddom2", "true", "ypmid", Boolean.TRUE, null);
        sdoms = (SignedDomains) response.getEntity();

        assertNotNull(sdoms);
        list = sdoms.getDomains();
        assertNotNull(list);
        assertEquals(list.size(), 0);

        zms.deleteTopLevelDomain(mockDomRsrcCtx, "SignedDom1", auditRef);
        zms.deleteTopLevelDomain(mockDomRsrcCtx, "SignedDom2", auditRef);
    }

    @Test
    public void testGetSignedDomainsNotModified() {

        TopLevelDomain dom1 = createTopLevelDomainObject("SignedDom1",
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        // set the meta attributes for domain

        DomainMeta meta = createDomainMetaObject("Tenant Domain1", null, true, false, "12345", 0);
        zms.putDomainMeta(mockDomRsrcCtx, "signeddom1", auditRef, meta);

        zms.privateKey = new ServerPrivateKey(Crypto.loadPrivateKey(Crypto.ybase64DecodeString(privKey)), "0");

        Authority principalAuthority = new com.yahoo.athenz.common.server.debug.DebugPrincipalAuthority();
        Principal sysPrincipal = principalAuthority.authenticate("v=U1;d=sys;n=zts;s=signature",
                "10.11.12.13", "GET", null);
        ResourceContext rsrcCtx = createResourceContext(sysPrincipal);

        EntityTag eTag = new EntityTag(Timestamp.fromCurrentTime().toString());
        Response response = zms.getSignedDomains(rsrcCtx, "signeddom1", null, null, Boolean.TRUE, eTag.toString());
        assertEquals(response.getStatus(), 304);

        zms.deleteTopLevelDomain(mockDomRsrcCtx, "SignedDom1", auditRef);
    }

    @Test
    public void testGetSignedDomainsException503() {

        ZMSImpl zmsImpl = zmsInit();

        DBService dbService = Mockito.mock(DBService.class);
        Mockito.when(dbService.getDomain("signeddom1", true)).thenThrow(new ResourceException(503));
        zmsImpl.dbService = dbService;

        Authority principalAuthority = new com.yahoo.athenz.common.server.debug.DebugPrincipalAuthority();
        Principal sysPrincipal = principalAuthority.authenticate("v=U1;d=sys;n=zts;s=signature",
                "10.11.12.13", "GET", null);
        ResourceContext rsrcCtx = createResourceContext(sysPrincipal);

        try {
            zmsImpl.getSignedDomains(rsrcCtx, "signeddom1", null, null, Boolean.TRUE, null);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 503);
        }

        zmsImpl.objectStore.clearConnections();
    }

    @Test
    public void testGetSignedDomainsException404() {

        ZMSImpl zmsImpl = zmsInit();

        DBService dbService = Mockito.mock(DBService.class);
        Mockito.when(dbService.getDomain("signeddom1", true)).thenThrow(new ResourceException(404));
        zmsImpl.dbService = dbService;

        // now signed domains with unknown domain name

        Authority principalAuthority = new com.yahoo.athenz.common.server.debug.DebugPrincipalAuthority();
        Principal sysPrincipal = principalAuthority.authenticate("v=U1;d=sys;n=zts;s=signature",
                "10.11.12.13", "GET", null);
        ResourceContext rsrcCtx = createResourceContext(sysPrincipal);

        Response response = zmsImpl.getSignedDomains(rsrcCtx, "signeddom1", null, null, Boolean.TRUE, null);
        SignedDomains sdoms = (SignedDomains) response.getEntity();

        assertNotNull(sdoms);
        List<SignedDomain> list = sdoms.getDomains();
        assertEquals(0, list.size());

        zmsImpl.objectStore.clearConnections();
    }

    @Test
    public void testReceiveSignedDomainDataDisabled() {

        // create multiple top level domains
        TopLevelDomain dom1 = createTopLevelDomainObject("SignedDom1Disabled",
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        // load the domain into cache and set the enabled to false

        zms.getAthenzDomain("signeddom1disabled", true);
        ObjectStoreConnection conn = zms.dbService.store.getConnection(true, false);
        zms.dbService.getAthenzDomainFromCache(conn, "signeddom1disabled").getDomain().setEnabled(false);

        // get the domain which would return from cache

        SignedDomain signedDomain = zms.retrieveSignedDomainData("signeddom1disabled", 0, false);
        assertFalse(signedDomain.getDomain().getEnabled());

        zms.deleteTopLevelDomain(mockDomRsrcCtx, "signeddom1disabled", auditRef);
    }

    @Test
    public void testReceiveSignedDomainDataAuditExpiryFields() {

        final String domainName = "signed-dom-fields";
        TopLevelDomain dom1 = createTopLevelDomainObject(domainName,
                "Test Domain1", "testOrg", adminUser);
        dom1.setAuditEnabled(true);
        dom1.setTokenExpiryMins(10);
        dom1.setRoleCertExpiryMins(20);
        dom1.setServiceCertExpiryMins(30);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        // get the domain which would return from cache

        SignedDomain signedDomain = zms.retrieveSignedDomainData(domainName, 0, false);
        assertTrue(signedDomain.getDomain().getAuditEnabled());
        assertEquals(Integer.valueOf(10), signedDomain.getDomain().getTokenExpiryMins());
        assertEquals(Integer.valueOf(20), signedDomain.getDomain().getRoleCertExpiryMins());
        assertEquals(Integer.valueOf(30), signedDomain.getDomain().getServiceCertExpiryMins());

        zms.deleteTopLevelDomain(mockDomRsrcCtx, domainName, auditRef);
    }

    @Test
    public void testValidateTenancyObject() {

        Tenancy tenant = new Tenancy().setDomain("athenz").setService("sports.provider");
        assertTrue(zms.validateTenancyObject(tenant, "athenz", "sports.provider"));
        assertFalse(zms.validateTenancyObject(tenant, "athens", "sports.provider"));
        assertFalse(zms.validateTenancyObject(tenant, "athenz", "sports.providers"));
    }

    @Test
    public void testPutTenantInvalidObject() {

        Tenancy tenant = new Tenancy().setDomain("sports").setService("athenz.provider");
        try {
            zms.putTenant(mockDomRsrcCtx, "athenz", "provider", "weather", auditRef, tenant);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 400);
        }
    }

    @Test
    public void testPutTenantInvalidService() {

        TopLevelDomain dom1 = createTopLevelDomainObject("providerdomain2",
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        Tenancy tenant = new Tenancy().setDomain("sports").setService("providerdomain2.service");
        try {
            zms.putTenant(mockDomRsrcCtx, "providerdomain2", "service", "sports", auditRef, tenant);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 404);
        }

        zms.deleteTopLevelDomain(mockDomRsrcCtx, "providerdomain2", auditRef);
    }

    @Test
    public void testPutTenant() {

        TopLevelDomain dom1 = createTopLevelDomainObject("providerdomain",
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        ServiceIdentity service = new ServiceIdentity();
        service.setName(ZMSUtils.serviceResourceName("providerdomain", "api"));
        zms.putServiceIdentity(mockDomRsrcCtx, "providerdomain", "api", auditRef, service);

        Tenancy tenant = new Tenancy().setDomain("sports").setService("providerdomain.api");
        zms.putTenant(mockDomRsrcCtx, "providerdomain", "api", "sports", auditRef, tenant);

        Role role = zms.getRole(mockDomRsrcCtx, "providerdomain", "api.tenant.sports.admin", false, false, false);
        assertNotNull(role);
        assertEquals(role.getTrust(), "sports");

        Policy policy = zms.getPolicy(mockDomRsrcCtx, "providerdomain", "api.tenant.sports.admin");
        assertNotNull(policy);
        assertEquals(policy.getAssertions().size(), 1);
        Assertion assertion = policy.getAssertions().get(0);
        assertEquals(assertion.getAction(), "*");
        assertEquals(assertion.getRole(), "providerdomain:role.api.tenant.sports.admin");
        assertEquals(assertion.getResource(), "providerdomain:service.api.tenant.sports.*");

        zms.deleteTopLevelDomain(mockDomRsrcCtx, "providerdomain", auditRef);
    }

    @Test
    public void testDeleteTenant() {

        TopLevelDomain dom1 = createTopLevelDomainObject("providerdomaindelete",
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        ServiceIdentity service = new ServiceIdentity();
        service.setName(ZMSUtils.serviceResourceName("providerdomaindelete", "api"));
        zms.putServiceIdentity(mockDomRsrcCtx, "providerdomaindelete", "api", auditRef, service);

        Tenancy tenant = new Tenancy().setDomain("sports").setService("providerdomaindelete.api");
        zms.putTenant(mockDomRsrcCtx, "providerdomaindelete", "api", "sports", auditRef, tenant);

        Role role = zms.getRole(mockDomRsrcCtx, "providerdomaindelete", "api.tenant.sports.admin", false, false, false);
        assertNotNull(role);

        Policy policy = zms.getPolicy(mockDomRsrcCtx, "providerdomaindelete", "api.tenant.sports.admin");
        assertNotNull(policy);

        zms.deleteTenant(mockDomRsrcCtx, "providerdomaindelete", "api", "sports", auditRef);

        try {
            zms.getRole(mockDomRsrcCtx, "providerdomaindelete", "api.tenant.sports.admin", false, false, false);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 404);
        }

        try {
            zms.getPolicy(mockDomRsrcCtx, "providerdomaindelete", "api.tenant.sports.admin");
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 404);
        }

        zms.deleteTopLevelDomain(mockDomRsrcCtx, "providerdomaindelete", auditRef);
    }

    @Test
    public void testDeleteTenantInvalidService() {

        TopLevelDomain dom1 = createTopLevelDomainObject("providerdomaindelete2",
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        try {
            zms.deleteTenant(mockDomRsrcCtx, "providerdomaindelete2", "api", "sports", auditRef);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 404);
        }

        zms.deleteTopLevelDomain(mockDomRsrcCtx, "providerdomaindelete2", auditRef);
    }

    @Test
    public void testValidateTenantResourceGroupRolesObject() {

        List<TenantRoleAction> list = new ArrayList<>();
        list.add(new TenantRoleAction().setRole("role").setAction("action"));

        TenantResourceGroupRoles roles = new TenantResourceGroupRoles()
                .setTenant("tenant").setDomain("domain").setResourceGroup("resourcegroup")
                .setService("service").setRoles(list);

        assertTrue(zms.validateTenantResourceGroupRolesObject(roles, "domain", "service",
                "tenant", "resourcegroup"));

        assertFalse(zms.validateTenantResourceGroupRolesObject(roles, "domain1", "service",
                "tenant", "resourcegroup"));
        assertFalse(zms.validateTenantResourceGroupRolesObject(roles, "domain", "service1",
                "tenant", "resourcegroup"));
        assertFalse(zms.validateTenantResourceGroupRolesObject(roles, "domain", "service",
                "tenant1", "resourcegroup"));
        assertFalse(zms.validateTenantResourceGroupRolesObject(roles, "domain", "service",
                "tenant", "resourcegroup1"));

        list.clear();
        assertFalse(zms.validateTenantResourceGroupRolesObject(roles, "domain", "service",
                "tenant", "resourcegroup"));

        roles.setRoles(null);
        assertFalse(zms.validateTenantResourceGroupRolesObject(roles, "domain", "service",
                "tenant", "resourcegroup"));
    }

    @Test
    public void testValidateProviderResourceGroupRolesObject() {

        List<TenantRoleAction> list = new ArrayList<>();
        list.add(new TenantRoleAction().setRole("role").setAction("action"));

        ProviderResourceGroupRoles roles = new ProviderResourceGroupRoles()
                .setTenant("tenant").setDomain("domain").setResourceGroup("resourcegroup")
                .setService("service").setRoles(list);

        assertTrue(zms.validateProviderResourceGroupRolesObject(roles, "domain", "service",
                "tenant", "resourcegroup"));

        assertFalse(zms.validateProviderResourceGroupRolesObject(roles, "domain1", "service",
                "tenant", "resourcegroup"));
        assertFalse(zms.validateProviderResourceGroupRolesObject(roles, "domain", "service1",
                "tenant", "resourcegroup"));
        assertFalse(zms.validateProviderResourceGroupRolesObject(roles, "domain", "service",
                "tenant1", "resourcegroup"));
        assertFalse(zms.validateProviderResourceGroupRolesObject(roles, "domain", "service",
                "tenant", "resourcegroup1"));

        list.clear();
        assertFalse(zms.validateProviderResourceGroupRolesObject(roles, "domain", "service",
                "tenant", "resourcegroup"));

        roles.setRoles(null);
        assertFalse(zms.validateProviderResourceGroupRolesObject(roles, "domain", "service",
                "tenant", "resourcegroup"));
    }

    @Test
    public void testTenancyIdenticalProviderTenantDomains() {

        List<TenantRoleAction> roleActions = new ArrayList<>();
        for (Struct.Field f : TABLE_PROVIDER_ROLE_ACTIONS) {
            roleActions.add(new TenantRoleAction().setRole(f.name()).setAction(
                    (String) f.value()));
        }
        String tenantDomain = "identicaldomain";
        String providerDomain = "identicaldomain";
        String providerService  = "storage";
        String providerFullService = providerDomain + "." + providerService;
        String resourceGroup = "group1";

        TenantResourceGroupRoles tenantRoles = new TenantResourceGroupRoles()
                .setDomain(providerDomain)
                .setService(providerService)
                .setTenant(tenantDomain)
                .setRoles(roleActions).setResourceGroup(resourceGroup);

        try {
            zms.putTenantResourceGroupRoles(mockDomRsrcCtx, providerDomain, providerService,
                    tenantDomain, resourceGroup, auditRef, tenantRoles);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 400);
            assertTrue(ex.getMessage().contains("Provider and tenant domains cannot be the same"));
        }

        Tenancy tenant = new Tenancy().setDomain(tenantDomain).setService(providerFullService);
        try {
            zms.putTenant(mockDomRsrcCtx, providerDomain, providerService, tenantDomain,
                    auditRef, tenant);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 400);
            assertTrue(ex.getMessage().contains("Provider and tenant domains cannot be the same"));
        }

        tenant = createTenantObject(tenantDomain, providerFullService);
        try {
            zms.putTenancy(mockDomRsrcCtx, tenantDomain, providerFullService, auditRef, tenant);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 400);
            assertTrue(ex.getMessage().contains("Provider and tenant domains cannot be the same"));
        }

        ProviderResourceGroupRoles providerRoles = new ProviderResourceGroupRoles()
                .setDomain(providerDomain).setService(providerService)
                .setTenant(tenantDomain).setRoles(roleActions)
                .setResourceGroup(resourceGroup);

        try {
            zms.putProviderResourceGroupRoles(mockDomRsrcCtx, tenantDomain, providerDomain,
                    providerService, resourceGroup, auditRef, providerRoles);
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 400);
            assertTrue(ex.getMessage().contains("Provider and tenant domains cannot be the same"));
        }
    }

    @Test
    public void testGetAuthorityInvalid() {
        assertNull(zms.getAuthority("invalid.class"));
    }

    @Test
    public void testLoadAuditRefValidator(){
        System.setProperty(ZMSConsts.ZMS_PROP_AUDIT_REF_VALIDATOR_FACTORY_CLASS, "com.yahoo.athenz.zms.audit.MockAuditReferenceValidatorFactoryImpl");
        zms.loadAuditRefValidator();
        assertNotNull(zms.auditReferenceValidator);
        System.clearProperty(ZMSConsts.ZMS_PROP_AUDIT_REF_VALIDATOR_FACTORY_CLASS);
    }

    @Test
    public void testNullLoadAuditRefValidator(){
        System.setProperty(ZMSConsts.ZMS_PROP_AUDIT_REF_VALIDATOR_FACTORY_CLASS, "does.not.exist");
        try {
            zms.loadAuditRefValidator();
        } catch (IllegalArgumentException e) {
            assertEquals(e.getMessage(), "Invalid audit reference factory class");
        }

        System.clearProperty(ZMSConsts.ZMS_PROP_AUDIT_REF_VALIDATOR_FACTORY_CLASS);
    }

    @Test
    public void testDefaultValuesOnCreation() {

        TestAuditLogger alogger = new TestAuditLogger();
        ZMSImpl zmsImpl = getZmsImpl(alogger);
        assertEquals(zmsImpl.virtualDomainLimit, 5);
        assertNull(zmsImpl.userDomainAliasPrefix);
        assertEquals(zmsImpl.signedPolicyTimeout, 1000 * 604800);
    }

    @Test
    public void testConfigOverridesOnCreation() {

        TestAuditLogger alogger = new TestAuditLogger();

        System.setProperty(ZMSConsts.ZMS_PROP_USER_DOMAIN_ALIAS, "xyz");
        System.setProperty(ZMSConsts.ZMS_PROP_SIGNED_POLICY_TIMEOUT, "86400");
        System.setProperty(ZMSConsts.ZMS_PROP_VIRTUAL_DOMAIN_LIMIT, "10");
        System.setProperty(ZMSConsts.ZMS_PROP_CORS_ORIGIN_LIST, "a.com,b.com");

        ZMSImpl zmsImpl = getZmsImpl(alogger);
        assertEquals(zmsImpl.virtualDomainLimit, 10);
        assertEquals(zmsImpl.userDomainAliasPrefix, "xyz.");
        assertEquals(zmsImpl.signedPolicyTimeout, 1000 * 86400);

        assertTrue(zmsImpl.isValidCORSOrigin("a.com"));

        System.clearProperty(ZMSConsts.ZMS_PROP_USER_DOMAIN_ALIAS);
        System.clearProperty(ZMSConsts.ZMS_PROP_SIGNED_POLICY_TIMEOUT);
        System.clearProperty(ZMSConsts.ZMS_PROP_VIRTUAL_DOMAIN_LIMIT);
        System.clearProperty(ZMSConsts.ZMS_PROP_CORS_ORIGIN_LIST);
    }

    @Test
    public void testPostSubDomainWithInvalidProductId() {

        TestAuditLogger alogger = new TestAuditLogger();
        System.setProperty(ZMSConsts.ZMS_PROP_PRODUCT_ID_SUPPORT, "true");

        ZMSImpl zmsImpl = getZmsImpl(alogger);

        TopLevelDomain dom1 = createTopLevelDomainObject("AddSubDom1",
                "Test Domain1", "testOrg", adminUser);
        zmsImpl.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        DomainMeta meta = createDomainMetaObject("Test Domain1", "testOrg",
                true, true, "12345", 1001);
        zmsImpl.putDomainMeta(mockDomRsrcCtx, "AddSubDom1", auditRef, meta);
        zmsImpl.putDomainSystemMeta(mockDomRsrcCtx, "AddSubDom1", "auditenabled", auditRef, meta);

        SubDomain dom2 = createSubDomainObject("AddSubDom2", "AddSubDom1",
                "Test Domain2", "testOrg", adminUser);
        dom2.setYpmId(-1);

        try {
            zmsImpl.postSubDomain(mockDomRsrcCtx, "AddSubDom1", auditRef, dom2);
            fail("ProductId error not thrown.");
        } catch (ResourceException e) {
            assertEquals(e.getCode(), 400);
        }

        zmsImpl.deleteTopLevelDomain(mockDomRsrcCtx, "AddSubDom1", auditRef);
        System.clearProperty(ZMSConsts.ZMS_PROP_PRODUCT_ID_SUPPORT);
    }


    @Test
    public void testGetPrincipalDomain() {
        Principal principal = SimplePrincipal.create("sports", "api",
                "creds", 0, new PrincipalAuthority());
        ResourceContext ctx = createResourceContext(principal);

        assertEquals(zms.getPrincipalDomain(ctx), "sports");
    }

    @Test
    public void testGetPrincipalDomainNull() {

        ResourceContext ctx = createResourceContext(null);
        assertNull(zms.getPrincipalDomain(ctx));
    }

    private RoleSystemMeta createRoleSystemMetaObject(Boolean auditEnabled) {

        RoleSystemMeta meta = new RoleSystemMeta();

        if (auditEnabled != null) {
            meta.setAuditEnabled(auditEnabled);
        }
        return meta;
    }

    private GroupSystemMeta createGroupSystemMetaObject(Boolean auditEnabled) {

        GroupSystemMeta meta = new GroupSystemMeta();

        if (auditEnabled != null) {
            meta.setAuditEnabled(auditEnabled);
        }
        return meta;
    }

    private void setupPrincipalRoleSystemMetaDelete(ZMSImpl zms, final String principal,
            final String domainName, final String attributeName) {

        Role role = createRoleObject("sys.auth", "metaroleadmin", null, principal, null);
        zms.putRole(mockDomRsrcCtx, "sys.auth", "metaroleadmin", auditRef, role);

        Policy policy = new Policy();
        policy.setName("metaroleadmin");

        Assertion assertion = new Assertion();
        assertion.setAction("delete");
        assertion.setEffect(AssertionEffect.ALLOW);
        assertion.setResource("sys.auth:meta.role." + attributeName + "." + domainName);
        assertion.setRole("sys.auth:role.metaroleadmin");

        List<Assertion> assertList = new ArrayList<>();
        assertList.add(assertion);

        policy.setAssertions(assertList);

        zms.putPolicy(mockDomRsrcCtx, "sys.auth", "metaroleadmin", auditRef, policy);
    }

    private void cleanupPrincipalRoleSystemMetaDelete(ZMSImpl zms) {

        zms.deleteRole(mockDomRsrcCtx, "sys.auth", "metaroleadmin", auditRef);
        zms.deletePolicy(mockDomRsrcCtx, "sys.auth", "metaroleadmin", auditRef);
    }

    @Test
    public void testPutRoleSystemMeta() {

        TopLevelDomain dom1 = createTopLevelDomainObject("rolesystemmetadom1",
                "Role System Meta Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        DomainMeta meta = createDomainMetaObject("Domain Meta for Role System Meta test", "NewOrg",
                true, true, "12345", 1001);
        zms.putDomainMeta(mockDomRsrcCtx, "rolesystemmetadom1", auditRef, meta);
        zms.putDomainSystemMeta(mockDomRsrcCtx, "rolesystemmetadom1", "auditenabled", auditRef, meta);

        Role role1 = createRoleObject("rolesystemmetadom1", "role1", null,
                "user.john", "user.jane");
        zms.putRole(mockDomRsrcCtx, "rolesystemmetadom1", "role1", auditRef, role1);

        RoleSystemMeta rsm = createRoleSystemMetaObject(true);
        zms.putRoleSystemMeta(mockDomRsrcCtx, "rolesystemmetadom1", "role1", "auditenabled", auditRef, rsm);

        Role resRole1 = zms.getRole(mockDomRsrcCtx, "rolesystemmetadom1", "role1", true, false, false);

        assertNotNull(resRole1);
        assertTrue(resRole1.getAuditEnabled());

        zms.deleteTopLevelDomain(mockDomRsrcCtx, "rolesystemmetadom1", auditRef);
    }

    @Test
    public void testPutRoleSystemMetaMissingAuditRef() {

        TopLevelDomain dom1 = createTopLevelDomainObject("rolesystemmetadom1",
                "Role System Meta Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        DomainMeta meta = createDomainMetaObject("Domain Meta for Role System Meta test", "NewOrg",
                true, true, "12345", 1001);
        zms.putDomainMeta(mockDomRsrcCtx, "rolesystemmetadom1", auditRef, meta);
        zms.putDomainSystemMeta(mockDomRsrcCtx, "rolesystemmetadom1", "auditenabled", auditRef, meta);

        Role role1 = createRoleObject("rolesystemmetadom1", "role1", null,
                "user.john", "user.jane");
        zms.putRole(mockDomRsrcCtx, "rolesystemmetadom1", "role1", auditRef, role1);

        RoleSystemMeta rsm = createRoleSystemMetaObject(true);

        try {
            zms.putRoleSystemMeta(mockDomRsrcCtx, "rolesystemmetadom1", "role1", "auditenabled", null, rsm);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 400);
            assertTrue(ex.getMessage().contains("Audit reference required"));
        } finally {
            zms.deleteTopLevelDomain(mockDomRsrcCtx, "rolesystemmetadom1", auditRef);
        }
    }

    @Test
    public void testPutRoleSystemMetaThrowException() {

        TestAuditLogger alogger = new TestAuditLogger();
        ZMSImpl zmsImpl = getZmsImpl(alogger);
        RoleSystemMeta rsm = new RoleSystemMeta();
        rsm.setAuditEnabled(false);

        try {
            zmsImpl.putRoleSystemMeta(mockDomRsrcCtx, "rolesystemmetadom1", "role1", "auditenabled", null, rsm);
            fail("notfounderror not thrown.");
        } catch (ResourceException e) {
            assertEquals(404, e.getCode());
        }
    }

    @Test
    public void testIsAllowedSystemMetaDelete(){

        TestAuditLogger alogger = new TestAuditLogger();
        System.setProperty(ZMSConsts.ZMS_PROP_PRODUCT_ID_SUPPORT, "true");
        ZMSImpl zmsImpl = getZmsImpl(alogger);

        assertFalse(zmsImpl.isAllowedSystemMetaDelete(mockDomRsrcCtx.principal(), "mockdom1", "auditenabled", "role"));
        setupPrincipalRoleSystemMetaDelete(zmsImpl, mockDomRsrcCtx.principal().getFullName(), "mockdom1", "auditenabled");
        assertTrue(zmsImpl.isAllowedSystemMetaDelete(mockDomRsrcCtx.principal(), "mockdom1", "auditenabled", "role"));
        cleanupPrincipalRoleSystemMetaDelete(zmsImpl);

        System.clearProperty(ZMSConsts.ZMS_PROP_PRODUCT_ID_SUPPORT);
    }

    private RoleMeta createRoleMetaObject(Boolean selfServe) {

        RoleMeta meta = new RoleMeta();

        if (selfServe != null) {
            meta.setSelfServe(selfServe);
        }
        return meta;
    }

    @Test
    public void testPutRoleMeta() {

        TopLevelDomain dom1 = createTopLevelDomainObject("rolemetadom1",
                "Role Meta Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        Role role1 = createRoleObject("rolemetadom1", "role1", null,
                "user.john", "user.jane");
        zms.putRole(mockDomRsrcCtx, "rolemetadom1", "role1", auditRef, role1);

        RoleMeta rm = createRoleMetaObject(true);
        rm.setMemberExpiryDays(45);
        rm.setCertExpiryMins(55);
        rm.setServiceExpiryDays(45);
        rm.setTokenExpiryMins(65);
        rm.setMemberReviewDays(70);
        rm.setServiceReviewDays(80);
        rm.setSignAlgorithm("ec");
        zms.putRoleMeta(mockDomRsrcCtx, "rolemetadom1", "role1", auditRef, rm);

        Role resRole1 = zms.getRole(mockDomRsrcCtx, "rolemetadom1", "role1", true, false, false);

        assertNotNull(resRole1);
        assertTrue(resRole1.getSelfServe());
        assertEquals(resRole1.getMemberExpiryDays(), Integer.valueOf(45));
        assertEquals(resRole1.getCertExpiryMins(), Integer.valueOf(55));
        assertEquals(resRole1.getTokenExpiryMins(), Integer.valueOf(65));
        assertEquals(resRole1.getServiceExpiryDays(), Integer.valueOf(45));
        assertEquals(resRole1.getMemberReviewDays(), Integer.valueOf(70));
        assertEquals(resRole1.getServiceReviewDays(), Integer.valueOf(80));
        assertEquals(resRole1.getSignAlgorithm(), "ec");

        // if we pass a null for the expiry days (e.g. old client)
        // then we're not going to modify the value

        RoleMeta rm2 = createRoleMetaObject(false);
        zms.putRoleMeta(mockDomRsrcCtx, "rolemetadom1", "role1", auditRef, rm2);

        resRole1 = zms.getRole(mockDomRsrcCtx, "rolemetadom1", "role1", true, false, false);

        assertNotNull(resRole1);
        assertNull(resRole1.getSelfServe());
        assertEquals(resRole1.getMemberExpiryDays(), Integer.valueOf(45));
        assertEquals(resRole1.getServiceExpiryDays(), Integer.valueOf(45));
        assertEquals(resRole1.getCertExpiryMins(), Integer.valueOf(55));
        assertEquals(resRole1.getTokenExpiryMins(), Integer.valueOf(65));
        assertEquals(resRole1.getMemberReviewDays(), Integer.valueOf(70));
        assertEquals(resRole1.getServiceReviewDays(), Integer.valueOf(80));

        // now let's reset to 0

        RoleMeta rm3 = createRoleMetaObject(false);
        rm3.setMemberExpiryDays(0);
        rm3.setServiceExpiryDays(0);
        rm3.setCertExpiryMins(0);
        rm3.setTokenExpiryMins(85);
        rm3.setMemberReviewDays(0);
        rm3.setServiceReviewDays(0);
        zms.putRoleMeta(mockDomRsrcCtx, "rolemetadom1", "role1", auditRef, rm3);

        resRole1 = zms.getRole(mockDomRsrcCtx, "rolemetadom1", "role1", true, false, false);

        assertNotNull(resRole1);
        assertNull(resRole1.getSelfServe());
        assertNull(resRole1.getMemberExpiryDays());
        assertNull(resRole1.getServiceExpiryDays());
        assertNull(resRole1.getCertExpiryMins());
        assertEquals(resRole1.getTokenExpiryMins(), Integer.valueOf(85));
        assertNull(resRole1.getMemberReviewDays());
        assertNull(resRole1.getServiceReviewDays());

        // invalid negative values

        RoleMeta rm4 = createRoleMetaObject(false);
        rm4.setMemberExpiryDays(-10);
        try {
            zms.putRoleMeta(mockDomRsrcCtx, "rolemetadom1", "role1", auditRef, rm4);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), ResourceException.BAD_REQUEST);
        }

        rm4.setMemberExpiryDays(10);
        rm4.setServiceExpiryDays(-10);
        try {
            zms.putRoleMeta(mockDomRsrcCtx, "rolemetadom1", "role1", auditRef, rm4);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), ResourceException.BAD_REQUEST);
        }

        rm4.setMemberExpiryDays(10);
        rm4.setServiceExpiryDays(10);
        rm4.setCertExpiryMins(-10);
        try {
            zms.putRoleMeta(mockDomRsrcCtx, "rolemetadom1", "role1", auditRef, rm4);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), ResourceException.BAD_REQUEST);
        }

        rm4.setMemberExpiryDays(10);
        rm4.setServiceExpiryDays(10);
        rm4.setCertExpiryMins(10);
        rm4.setTokenExpiryMins(-10);
        try {
            zms.putRoleMeta(mockDomRsrcCtx, "rolemetadom1", "role1", auditRef, rm4);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), ResourceException.BAD_REQUEST);
        }

        rm4.setMemberExpiryDays(10);
        rm4.setServiceExpiryDays(10);
        rm4.setCertExpiryMins(10);
        rm4.setTokenExpiryMins(10);
        rm4.setMemberReviewDays(-10);
        try {
            zms.putRoleMeta(mockDomRsrcCtx, "rolemetadom1", "role1", auditRef, rm4);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), ResourceException.BAD_REQUEST);
        }

        rm4.setMemberExpiryDays(10);
        rm4.setServiceExpiryDays(10);
        rm4.setCertExpiryMins(10);
        rm4.setTokenExpiryMins(10);
        rm4.setMemberReviewDays(10);
        rm4.setServiceReviewDays(-10);
        try {
            zms.putRoleMeta(mockDomRsrcCtx, "rolemetadom1", "role1", auditRef, rm4);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), ResourceException.BAD_REQUEST);
        }

        zms.deleteTopLevelDomain(mockDomRsrcCtx, "rolemetadom1", auditRef);
    }

    @Test
    public void testPutRoleMetaMissingAuditRef() {

        TopLevelDomain dom1 = createTopLevelDomainObject("rolemetadom1", "Role Meta Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        DomainMeta meta = createDomainMetaObject("Domain Meta for Role Meta test", "NewOrg",
                true, true, "12345", 1001);
        zms.putDomainMeta(mockDomRsrcCtx, "rolemetadom1", auditRef, meta);
        zms.putDomainSystemMeta(mockDomRsrcCtx, "rolemetadom1", "auditenabled", auditRef, meta);

        Role role1 = createRoleObject("rolemetadom1", "role1", null,
                "user.john", "user.jane");
        zms.putRole(mockDomRsrcCtx, "rolemetadom1", "role1", auditRef, role1);

        RoleSystemMeta rsm = createRoleSystemMetaObject(true);
        zms.putRoleSystemMeta(mockDomRsrcCtx, "rolemetadom1", "role1", "auditenabled", auditRef, rsm);

        RoleMeta rm = createRoleMetaObject(true);
        try {
            zms.putRoleMeta(mockDomRsrcCtx, "rolemetadom1", "role1", null, rm);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 400);
            assertTrue(ex.getMessage().contains("Audit reference required"));
        } finally {
            zms.deleteTopLevelDomain(mockDomRsrcCtx, "rolemetadom1", auditRef);
        }
    }

    @Test
    public void testPutRoleMetaThrowException() {

        TestAuditLogger alogger = new TestAuditLogger();
        ZMSImpl zmsImpl = getZmsImpl(alogger);
        RoleMeta rm = new RoleMeta();
        rm.setSelfServe(false);

        try {
            zmsImpl.putRoleMeta(mockDomRsrcCtx, "rolemetadom1", "role1", auditRef, rm);
            fail("notfounderror not thrown.");
        } catch (ResourceException e) {
            assertEquals(404, e.getCode());
        }
    }

    @Test
    public void testIsAllowedPutMembershipAccess(){
        TopLevelDomain dom1 = createTopLevelDomainObject("testdomain1",
                "Role Test Domain1", "testOrg", "user.user1");
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        Role role1 = createRoleObject("testdomain1", "testrole1", null,"user.user1", "user.jane");
        zms.putRole(mockDomRsrcCtx, "testdomain1", "testrole1", auditRef, role1);

        AthenzDomain domain = zms.getAthenzDomain("testdomain1", false);
        Role role = zms.getRoleFromDomain("testrole1", domain);

        assertTrue(zms.isAllowedPutMembershipAccess(mockDomRestRsrcCtx.principal(), domain, role.getName()));

        Authority principalAuthority = new com.yahoo.athenz.common.server.debug.DebugPrincipalAuthority();
        String unsignedCreds = "v=U1;d=user;n=john";
        final Principal rsrcPrince = SimplePrincipal.create("user", "john", unsignedCreds + ";s=signature",0, principalAuthority);
        assertNotNull(rsrcPrince);
        ((SimplePrincipal) rsrcPrince).setUnsignedCreds(unsignedCreds);

        assertFalse(zms.isAllowedPutMembershipAccess(rsrcPrince, domain, role.getName()));// some random user does not have access

        zms.deleteTopLevelDomain(mockDomRsrcCtx, "testdomain1", auditRef);
    }

    @Test
    public void testIsAllowedPutMembershipWithoutApproval() {

        TopLevelDomain dom1 = createTopLevelDomainObject("testdomain1","Role Test Domain1", "testOrg", "user.user1");
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        Role role1 = createRoleObject("testdomain1", "testrole1", null,"user.john", "user.jane");
        zms.putRole(mockDomRsrcCtx, "testdomain1", "testrole1", auditRef, role1);

        AthenzDomain domain = zms.getAthenzDomain("testdomain1", false);
        Role role = zms.getRoleFromDomain("testrole1", domain);

        assertTrue(zms.isAllowedPutMembershipWithoutApproval(mockDomRestRsrcCtx.principal(), domain, role));//admin allowed

        Authority principalAuthority = new com.yahoo.athenz.common.server.debug.DebugPrincipalAuthority();
        String unsignedCreds = "v=U1;d=user;n=john";
        final Principal rsrcPrince = SimplePrincipal.create("user", "john", unsignedCreds + ";s=signature",0, principalAuthority);
        assertNotNull(rsrcPrince);
        ((SimplePrincipal) rsrcPrince).setUnsignedCreds(unsignedCreds);

        assertFalse(zms.isAllowedPutMembershipWithoutApproval(rsrcPrince, domain, role));//other user not allowed

        DomainMeta meta = createDomainMetaObject("Domain Meta for Role Meta test", "testOrg",
                true, true, "12345", 1001);
        zms.putDomainMeta(mockDomRsrcCtx, "testdomain1", auditRef, meta);
        zms.putDomainSystemMeta(mockDomRsrcCtx, "testdomain1", "auditenabled", auditRef, meta);

        Role role2 = createRoleObject("testdomain1", "testrole2", null,"user.john", "user.jane");
        zms.putRole(mockDomRsrcCtx, "testdomain1", "testrole2", auditRef, role2);

        RoleSystemMeta rsm = createRoleSystemMetaObject(true);
        zms.putRoleSystemMeta(mockDomRsrcCtx, "testdomain1", "testrole2", "auditenabled", auditRef, rsm);

        Authority principalAuthority1 = new com.yahoo.athenz.common.server.debug.DebugPrincipalAuthority();
        String unsignedCreds1 = "v=U1;d=user;n=user1";
        final Principal adminPrinc = SimplePrincipal.create("user", "user1", unsignedCreds + ";s=signature",0, principalAuthority1);
        assertNotNull(adminPrinc);
        ((SimplePrincipal) adminPrinc).setUnsignedCreds(unsignedCreds1);

        domain = zms.getAthenzDomain("testdomain1", false);
        role = zms.getRoleFromDomain("testrole2", domain);
        assertFalse(zms.isAllowedPutMembershipWithoutApproval(adminPrinc, domain, role));//admin not allowed on audit enabled role

        zms.deleteTopLevelDomain(mockDomRsrcCtx, "testdomain1", auditRef);
    }

    @Test
    public void testIsAllowedPutMembership() {

        TopLevelDomain dom1 = createTopLevelDomainObject("testdomain1","Role Test Domain1", "testOrg", "user.user1");
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        Role role1 = createRoleObject("testdomain1", "testrole1", null,"user.john", "user.jane");
        zms.putRole(mockDomRsrcCtx, "testdomain1", "testrole1", auditRef, role1);

        AthenzDomain domain = zms.getAthenzDomain("testdomain1", false);
        Role role = zms.getRoleFromDomain("testrole1", domain);

        RoleMember roleMember = new RoleMember().setMemberName("user.user1");

        assertTrue(zms.isAllowedPutMembership(mockDomRestRsrcCtx.principal(), domain, role, roleMember));//admin allowed
        assertTrue(roleMember.getApproved());

        Authority principalAuthority = new com.yahoo.athenz.common.server.debug.DebugPrincipalAuthority();
        String unsignedCreds = "v=U1;d=user;n=bob";
        final Principal rsrcPrince = SimplePrincipal.create("user", "bob", unsignedCreds + ";s=signature",0, principalAuthority);
        assertNotNull(rsrcPrince);
        ((SimplePrincipal) rsrcPrince).setUnsignedCreds(unsignedCreds);

        roleMember = new RoleMember().setMemberName("user.bob");
        assertFalse(zms.isAllowedPutMembership(rsrcPrince, domain, role, roleMember));//bob trying to add himself

        // without self-serve bob is not allowed to add dave
        roleMember = new RoleMember().setMemberName("user.dave");
        assertFalse(zms.isAllowedPutMembership(rsrcPrince, domain, role, roleMember));//bob trying to add dave

        Role selfserverole = createRoleObject("testdomain1", "testrole2", null,"user.john", "user.jane");
        zms.putRole(mockDomRsrcCtx, "testdomain1", "testrole2", auditRef, selfserverole);

        RoleMeta rm = createRoleMetaObject(true);
        zms.putRoleMeta(mockDomRsrcCtx, "testdomain1", "testrole2",  auditRef, rm);

        domain = zms.getAthenzDomain("testdomain1", false);
        role = zms.getRoleFromDomain("testrole2", domain);

        roleMember = new RoleMember().setMemberName("user.bob");
        assertTrue(zms.isAllowedPutMembership(rsrcPrince, domain, role, roleMember));//bob trying to add himself
        assertFalse(roleMember.getApproved());

        // with self-serve bob is now allowed to add dave
        roleMember.setMemberName("user.dave");
        assertTrue(zms.isAllowedPutMembership(rsrcPrince, domain, role, roleMember));//bob trying to add dave
        assertFalse(roleMember.getApproved());

        DomainMeta meta = createDomainMetaObject("Domain Meta for Role Meta test", "testOrg",
                true, true, "12345", 1001);
        zms.putDomainMeta(mockDomRsrcCtx, "testdomain1", auditRef, meta);
        zms.putDomainSystemMeta(mockDomRsrcCtx, "testdomain1", "auditenabled", auditRef, meta);

        Role auditedRole = createRoleObject("testdomain1", "testrole3", null,"user.john", "user.jane");
        zms.putRole(mockDomRsrcCtx, "testdomain1", "testrole3", auditRef, auditedRole);

        RoleSystemMeta rsm = createRoleSystemMetaObject(true);
        zms.putRoleSystemMeta(mockDomRsrcCtx, "testdomain1", "testrole3", "auditenabled", auditRef, rsm);

        domain = zms.getAthenzDomain("testdomain1", false);
        role = zms.getRoleFromDomain("testrole3", domain);

        roleMember = new RoleMember().setMemberName("user.user1");
        assertTrue(zms.isAllowedPutMembership(mockDomRestRsrcCtx.principal(), domain, role, roleMember));//admin allowed
        assertFalse(roleMember.getApproved());

        roleMember = new RoleMember().setMemberName("user.bob");
        assertFalse(zms.isAllowedPutMembership(rsrcPrince, domain, role, roleMember));//bob trying to add himself not allowed

        roleMember = new RoleMember().setMemberName("user.dave");
        assertFalse(zms.isAllowedPutMembership(rsrcPrince, domain, role, roleMember));//bob trying to add dave not allowed

        zms.deleteTopLevelDomain(mockDomRsrcCtx, "testdomain1", auditRef);
    }

    @Test
    public void testPutMembershipSelfserveRole() {

        addMemberToSelfServeRoleWithUserIdentity();

        Role resrole = zms.getRole(mockDomRsrcCtx, "testdomain1", "testrole1", false, false, true);
        assertEquals(resrole.getRoleMembers().size(), 3);
        for (RoleMember rmem : resrole.getRoleMembers()) {
            if ("user.bob".equals(rmem.getMemberName())) {
                assertFalse(rmem.getApproved());
            }
        }
        zms.deleteTopLevelDomain(mockDomRsrcCtx, "testdomain1", auditRef);
    }

    private void addMemberToSelfServeRoleWithUserIdentity() {
        TopLevelDomain dom1 = createTopLevelDomainObject("testdomain1", "Approval test Domain1", "testOrg", "user.user1");
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        Role role1 = createRoleObject("testdomain1", "testrole1", null, "user.john", "user.jane");
        zms.putRole(mockDomRsrcCtx, "testdomain1", "testrole1", auditRef, role1);

        RoleMeta rm = createRoleMetaObject(true);
        zms.putRoleMeta(mockDomRsrcCtx, "testdomain1", "testrole1", auditRef, rm);

        //switch to user.bob principal to test selfserve role membership
        Authority principalAuthority = new com.yahoo.athenz.common.server.debug.DebugPrincipalAuthority();
        String unsignedCreds = "v=U1;d=user;n=bob";
        final Principal rsrcPrince = SimplePrincipal.create("user", "bob", unsignedCreds + ";s=signature", 0, principalAuthority);
        assertNotNull(rsrcPrince);
        ((SimplePrincipal) rsrcPrince).setUnsignedCreds(unsignedCreds);
        Mockito.when(mockDomRestRsrcCtx.principal()).thenReturn(rsrcPrince);
        Mockito.when(mockDomRsrcCtx.principal()).thenReturn(rsrcPrince);

        Membership mbr = new Membership();
        mbr.setMemberName("user.bob");
        mbr.setActive(false);
        mbr.setApproved(false);

        zms.putMembership(mockDomRsrcCtx, "testdomain1", "testrole1", "user.bob", auditRef, mbr);

        //revert back to admin principal
        Authority adminPrincipalAuthority = new com.yahoo.athenz.common.server.debug.DebugPrincipalAuthority();
        String adminUnsignedCreds = "v=U1;d=user;n=user1";
        // used with the mockDomRestRsrcCtx
        final Principal rsrcAdminPrince = SimplePrincipal.create("user", "user1", adminUnsignedCreds + ";s=signature",
                0, adminPrincipalAuthority);
        assertNotNull(rsrcAdminPrince);
        ((SimplePrincipal) rsrcAdminPrince).setUnsignedCreds(adminUnsignedCreds);

        Mockito.when(mockDomRestRsrcCtx.principal()).thenReturn(rsrcAdminPrince);
        Mockito.when(mockDomRsrcCtx.principal()).thenReturn(rsrcAdminPrince);
    }

    private void addMemberToSelfServeGroupWithUserIdentity(final String domainName, final String groupName) {

        TopLevelDomain dom1 = createTopLevelDomainObject(domainName, "Approval test Domain1", "testOrg", "user.user1");
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        Group group1 = createGroupObject(domainName, groupName, "user.john", "user.jane");
        zms.putGroup(mockDomRsrcCtx, domainName, groupName, auditRef, group1);

        GroupMeta rm = new GroupMeta().setSelfServe(true);
        zms.putGroupMeta(mockDomRsrcCtx, domainName, groupName, auditRef, rm);

        //switch to user.bob principal to test selfserve role membership
        Authority principalAuthority = new com.yahoo.athenz.common.server.debug.DebugPrincipalAuthority();
        String unsignedCreds = "v=U1;d=user;n=bob";
        final Principal rsrcPrince = SimplePrincipal.create("user", "bob", unsignedCreds + ";s=signature", 0, principalAuthority);
        assertNotNull(rsrcPrince);
        ((SimplePrincipal) rsrcPrince).setUnsignedCreds(unsignedCreds);
        Mockito.when(mockDomRestRsrcCtx.principal()).thenReturn(rsrcPrince);
        Mockito.when(mockDomRsrcCtx.principal()).thenReturn(rsrcPrince);

        GroupMembership mbr = new GroupMembership();
        mbr.setMemberName("user.bob");
        mbr.setActive(false);
        mbr.setApproved(false);

        zms.putGroupMembership(mockDomRsrcCtx, domainName, groupName, "user.bob", auditRef, mbr);

        //revert back to admin principal
        Authority adminPrincipalAuthority = new com.yahoo.athenz.common.server.debug.DebugPrincipalAuthority();
        String adminUnsignedCreds = "v=U1;d=user;n=user1";
        // used with the mockDomRestRsrcCtx
        final Principal rsrcAdminPrince = SimplePrincipal.create("user", "user1", adminUnsignedCreds + ";s=signature",
                0, adminPrincipalAuthority);
        assertNotNull(rsrcAdminPrince);
        ((SimplePrincipal) rsrcAdminPrince).setUnsignedCreds(adminUnsignedCreds);

        Mockito.when(mockDomRestRsrcCtx.principal()).thenReturn(rsrcAdminPrince);
        Mockito.when(mockDomRsrcCtx.principal()).thenReturn(rsrcAdminPrince);
    }

    @Test
    public void testPutMembershipDecisionSelfserveRoleApprove() {

        addMemberToSelfServeRoleWithUserIdentity();

        Role resrole = zms.getRole(mockDomRsrcCtx, "testdomain1", "testrole1", false, false, true);
        assertEquals(resrole.getRoleMembers().size(), 3);
        for (RoleMember rmem : resrole.getRoleMembers()) {
            if ("user.bob".equals(rmem.getMemberName())) {
                assertFalse(rmem.getApproved());
            }
        }
        Membership mbr = new Membership();
        mbr.setMemberName("user.bob");
        mbr.setActive(true);
        mbr.setApproved(true);
        zms.putMembershipDecision(mockDomRsrcCtx, "testdomain1", "testrole1", "user.bob", auditRef, mbr);

        resrole = zms.getRole(mockDomRsrcCtx, "testdomain1", "testrole1", false, false, false);
        assertEquals(resrole.getRoleMembers().size(), 3);
        for (RoleMember rmem : resrole.getRoleMembers()) {
            if ("user.bob".equals(rmem.getMemberName())) {
                assertTrue(rmem.getApproved());
            }
        }
        zms.deleteTopLevelDomain(mockDomRsrcCtx, "testdomain1", auditRef);
    }

    @Test
    public void testPutMembershipDecisionSelfserveRoleReject() {

        addMemberToSelfServeRoleWithUserIdentity();

        Role resrole = zms.getRole(mockDomRsrcCtx, "testdomain1", "testrole1", false, false, true);
        assertEquals(resrole.getRoleMembers().size(), 3);
        for (RoleMember rmem : resrole.getRoleMembers()) {
            if ("user.bob".equals(rmem.getMemberName())) {
                assertFalse(rmem.getApproved());
            }
        }
        Membership mbr = new Membership();
        mbr.setMemberName("user.bob");
        mbr.setActive(false);
        mbr.setApproved(false);
        zms.putMembershipDecision(mockDomRsrcCtx, "testdomain1", "testrole1", "user.bob", auditRef, mbr);

        resrole = zms.getRole(mockDomRsrcCtx, "testdomain1", "testrole1", false, false, false);
        assertEquals(resrole.getRoleMembers().size(), 2);

        zms.deleteTopLevelDomain(mockDomRsrcCtx, "testdomain1", auditRef);
    }

    private void setupPrincipalAuditedRoleApprovalByOrg(ZMSImpl zms, final String principal, final String org) {

        Role role = createRoleObject("sys.auth.audit.org", org, null, principal, null);
        zms.putRole(mockDomRsrcCtx, "sys.auth.audit.org", org, auditRef, role);

        Policy policy = new Policy();
        policy.setName(org);

        Assertion assertion = new Assertion();
        assertion.setAction("update");
        assertion.setEffect(AssertionEffect.ALLOW);
        assertion.setResource("sys.auth.audit.org:audit." + org);
        assertion.setRole("sys.auth.audit.org:role." + org);

        List<Assertion> assertList = new ArrayList<>();
        assertList.add(assertion);
        policy.setAssertions(assertList);

        zms.putPolicy(mockDomRsrcCtx, "sys.auth.audit.org", org, auditRef, policy);
    }

    private void setupPrincipalAuditedRoleApprovalByDomain(ZMSImpl zms, final String principal,
            final String domainName) {

        Role role = createRoleObject("sys.auth.audit.domain", domainName, null, principal, null);
        zms.putRole(mockDomRsrcCtx, "sys.auth.audit.domain", domainName, auditRef, role);

        Policy policy = new Policy();
        policy.setName(domainName);

        Assertion assertion = new Assertion();
        assertion.setAction("update");
        assertion.setEffect(AssertionEffect.ALLOW);
        assertion.setResource("sys.auth.audit.domain:audit." + domainName);
        assertion.setRole("sys.auth.audit.domain:role." + domainName);

        List<Assertion> assertList = new ArrayList<>();
        assertList.add(assertion);
        policy.setAssertions(assertList);

        zms.putPolicy(mockDomRsrcCtx, "sys.auth.audit.domain", domainName, auditRef, policy);
    }

    @Test
    public void testPutMembershipDecisionReviewEnabledRoleApprove() {

        final String domainName = "review-enabled-domain";
        TopLevelDomain dom1 = createTopLevelDomainObject(domainName, "Approval test Domain1",
                "testOrg", "user.user1");
        dom1.getAdminUsers().add("user.user2");
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        final String roleName = "review-role";
        Role role1 = createRoleObject(domainName, roleName, null, null, null);
        zms.putRole(mockDomRsrcCtx, domainName, roleName, auditRef, role1);

        RoleMeta rm = new RoleMeta().setReviewEnabled(true);
        zms.putRoleMeta(mockDomRsrcCtx, domainName, roleName, auditRef, rm);

        // switch to user.user2 principal to add a member to a role

        Authority principalAuthority = new com.yahoo.athenz.common.server.debug.DebugPrincipalAuthority();
        String unsignedCreds = "v=U1;d=user;n=user2";
        final Principal rsrcPrince = SimplePrincipal.create("user", "user2",
                unsignedCreds + ";s=signature", 0, principalAuthority);
        assertNotNull(rsrcPrince);
        ((SimplePrincipal) rsrcPrince).setUnsignedCreds(unsignedCreds);
        Mockito.when(mockDomRestRsrcCtx.principal()).thenReturn(rsrcPrince);
        Mockito.when(mockDomRsrcCtx.principal()).thenReturn(rsrcPrince);

        Membership mbr = new Membership();
        mbr.setMemberName("user.bob");
        mbr.setActive(false);
        mbr.setApproved(false);

        zms.putMembership(mockDomRsrcCtx, domainName, roleName, "user.bob", auditRef, mbr);

        // verify the user is added with pending state

        Role resrole = zms.getRole(mockDomRsrcCtx, domainName, roleName, false, false, true);
        assertEquals(resrole.getRoleMembers().size(), 1);
        assertEquals(resrole.getRoleMembers().get(0).getMemberName(), "user.bob");
        assertFalse(resrole.getRoleMembers().get(0).getApproved());

        // now try as the admin himself to approve this user and it must
        // be rejected since it has to be done by some other admin

        mbr = new Membership();
        mbr.setMemberName("user.bob");
        mbr.setActive(true);
        mbr.setApproved(true);

        try {
            zms.putMembershipDecision(mockDomRsrcCtx, domainName, roleName, "user.bob", auditRef, mbr);
            fail();
        } catch (ResourceException ex) {
            assertTrue(ex.getMessage().contains("cannot approve his/her own request"));
        }

        // revert back to admin principal

        Authority adminPrincipalAuthority = new com.yahoo.athenz.common.server.debug.DebugPrincipalAuthority();
        String adminUnsignedCreds = "v=U1;d=user;n=user1";
        final Principal rsrcAdminPrince = SimplePrincipal.create("user", "user1",
                adminUnsignedCreds + ";s=signature", 0, adminPrincipalAuthority);
        assertNotNull(rsrcAdminPrince);
        ((SimplePrincipal) rsrcAdminPrince).setUnsignedCreds(adminUnsignedCreds);

        Mockito.when(mockDomRestRsrcCtx.principal()).thenReturn(rsrcAdminPrince);
        Mockito.when(mockDomRsrcCtx.principal()).thenReturn(rsrcAdminPrince);

        // approve the message which should be successful

        zms.putMembershipDecision(mockDomRsrcCtx, domainName, roleName, "user.bob", auditRef, mbr);

        // verify the user is now active

        resrole = zms.getRole(mockDomRsrcCtx, domainName, roleName, false, false, true);
        assertEquals(resrole.getRoleMembers().size(), 1);
        assertEquals(resrole.getRoleMembers().get(0).getMemberName(), "user.bob");
        assertTrue(resrole.getRoleMembers().get(0).getApproved());

        // trying to approve the same user should return 404

        try {
            zms.putMembershipDecision(mockDomRsrcCtx, domainName, roleName, "user.bob", auditRef, mbr);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), ResourceException.NOT_FOUND);
        }

        // now try to approve another use which should also return 404

        mbr.setMemberName("user.joe");
        try {
            zms.putMembershipDecision(mockDomRsrcCtx, domainName, roleName, "user.joe", auditRef, mbr);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), ResourceException.NOT_FOUND);
        }

        zms.deleteTopLevelDomain(mockDomRsrcCtx, domainName, auditRef);
    }

    private void clenaupPrincipalAuditedRoleApprovalByOrg(ZMSImpl zms, final String org) {
        zms.deleteRole(mockDomRsrcCtx, "sys.auth.audit.org", org, auditRef);
        zms.deletePolicy(mockDomRsrcCtx, "sys.auth.audit.org", org, auditRef);
    }

    private void clenaupPrincipalAuditedRoleApprovalByDomain(ZMSImpl zms, final String domainName) {
        zms.deleteRole(mockDomRsrcCtx, "sys.auth.audit.domain", domainName, auditRef);
        zms.deletePolicy(mockDomRsrcCtx, "sys.auth.audit.domain", domainName, auditRef);
    }

    @Test
    public void testPutMembershipDecisionAuditEnabledRoleByOrg() {

        final String domainName = "testdomain1";
        final String roleName = "testrole1";

        TopLevelDomain dom1 = createTopLevelDomainObject(domainName, "Approval Test Domain1",
                "testOrg", "user.user1");
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);
        DomainMeta meta = createDomainMetaObject("Domain Meta for approval test", "testOrg",
                true, true, "12345", 1001);
        zms.putDomainMeta(mockDomRsrcCtx, domainName, auditRef, meta);
        zms.putDomainSystemMeta(mockDomRsrcCtx, domainName, "auditenabled", auditRef, meta);

        Role auditedRole = createRoleObject(domainName, roleName, null, "user.john", "user.jane");
        zms.putRole(mockDomRsrcCtx, domainName, roleName, auditRef, auditedRole);
        RoleSystemMeta rsm = createRoleSystemMetaObject(true);
        zms.putRoleSystemMeta(mockDomRsrcCtx, domainName, roleName, "auditenabled", auditRef, rsm);

        Membership mbr = new Membership();
        mbr.setMemberName("user.bob");
        mbr.setActive(false);
        mbr.setApproved(false);
        zms.putMembership(mockDomRsrcCtx, domainName, roleName, "user.bob", auditRef, mbr);

        Role resrole = zms.getRole(mockDomRsrcCtx, domainName, roleName, false, false, true);
        assertEquals(resrole.getRoleMembers().size(), 3);
        for (RoleMember rmem : resrole.getRoleMembers()) {
            if ("user.bob".equals(rmem.getMemberName())) {
                assertFalse(rmem.getApproved());
            }
        }

        setupPrincipalAuditedRoleApprovalByOrg(zms, "user.fury", "testOrg");

        mbr = new Membership();
        mbr.setMemberName("user.bob");
        mbr.setActive(true);
        mbr.setApproved(true);

        try {
            zms.putMembershipDecision(mockDomRsrcCtx, domainName, roleName, "user.bob", auditRef, mbr);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), ResourceException.FORBIDDEN);
        }

        Authority auditAdminPrincipalAuthority = new com.yahoo.athenz.common.server.debug.DebugPrincipalAuthority();
        String auditAdminUnsignedCreds = "v=U1;d=user;n=fury";
        // used with the mockDomRestRsrcCtx
        final Principal rsrcAuditAdminPrince = SimplePrincipal.create("user", "fury",
                auditAdminUnsignedCreds + ";s=signature", 0, auditAdminPrincipalAuthority);
        assertNotNull(rsrcAuditAdminPrince);
        ((SimplePrincipal) rsrcAuditAdminPrince).setUnsignedCreds(auditAdminUnsignedCreds);

        Mockito.when(mockDomRestRsrcCtx.principal()).thenReturn(rsrcAuditAdminPrince);
        Mockito.when(mockDomRsrcCtx.principal()).thenReturn(rsrcAuditAdminPrince);

        zms.putMembershipDecision(mockDomRsrcCtx, domainName, roleName, "user.bob", auditRef, mbr);

        //revert back to admin principal
        Authority adminPrincipalAuthority = new com.yahoo.athenz.common.server.debug.DebugPrincipalAuthority();
        String adminUnsignedCreds = "v=U1;d=user;n=user1";
        // used with the mockDomRestRsrcCtx
        final Principal rsrcAdminPrince = SimplePrincipal.create("user", "user1",
                adminUnsignedCreds + ";s=signature", 0, adminPrincipalAuthority);
        assertNotNull(rsrcAdminPrince);
        ((SimplePrincipal) rsrcAdminPrince).setUnsignedCreds(adminUnsignedCreds);

        Mockito.when(mockDomRestRsrcCtx.principal()).thenReturn(rsrcAdminPrince);
        Mockito.when(mockDomRsrcCtx.principal()).thenReturn(rsrcAdminPrince);

        resrole = zms.getRole(mockDomRsrcCtx, domainName, roleName, false, false, false);
        assertEquals(resrole.getRoleMembers().size(), 3);
        for (RoleMember rmem : resrole.getRoleMembers()) {
            if ("user.bob".equals(rmem.getMemberName())) {
                assertTrue(rmem.getApproved());
            }
        }

        clenaupPrincipalAuditedRoleApprovalByOrg(zms, "testOrg");
        zms.deleteTopLevelDomain(mockDomRsrcCtx, domainName, auditRef);
    }

    @Test
    public void testPutMembershipDecisionAuditEnabledRoleByDomain() {

        final String domainName = "testdomain1";
        final String roleName = "testrole1";

        TopLevelDomain dom1 = createTopLevelDomainObject(domainName, "Approval Test Domain1",
                "testOrg", "user.user1");
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);
        DomainMeta meta = createDomainMetaObject("Domain Meta for approval test", "testOrg",
                true, true, "12345", 1001);
        zms.putDomainMeta(mockDomRsrcCtx, domainName, auditRef, meta);
        zms.putDomainSystemMeta(mockDomRsrcCtx, domainName, "auditenabled", auditRef, meta);

        Role auditedRole = createRoleObject(domainName, roleName, null, "user.john", "user.jane");
        zms.putRole(mockDomRsrcCtx, domainName, roleName, auditRef, auditedRole);
        RoleSystemMeta rsm = createRoleSystemMetaObject(true);
        zms.putRoleSystemMeta(mockDomRsrcCtx, domainName, roleName, "auditenabled", auditRef, rsm);

        Membership mbr = new Membership();
        mbr.setMemberName("user.bob");
        mbr.setActive(false);
        mbr.setApproved(false);
        zms.putMembership(mockDomRsrcCtx, domainName, roleName, "user.bob", auditRef, mbr);

        Role resrole = zms.getRole(mockDomRsrcCtx, domainName, roleName, false, false, true);
        assertEquals(resrole.getRoleMembers().size(), 3);
        for (RoleMember rmem : resrole.getRoleMembers()) {
            if ("user.bob".equals(rmem.getMemberName())) {
                assertFalse(rmem.getApproved());
            }
        }

        setupPrincipalAuditedRoleApprovalByDomain(zms, "user.fury", domainName);

        mbr = new Membership();
        mbr.setMemberName("user.bob");
        mbr.setActive(true);
        mbr.setApproved(true);

        try {
            zms.putMembershipDecision(mockDomRsrcCtx, domainName, roleName, "user.bob", auditRef, mbr);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), ResourceException.FORBIDDEN);
        }

        Authority auditAdminPrincipalAuthority = new com.yahoo.athenz.common.server.debug.DebugPrincipalAuthority();
        String auditAdminUnsignedCreds = "v=U1;d=user;n=fury";
        // used with the mockDomRestRsrcCtx
        final Principal rsrcAuditAdminPrince = SimplePrincipal.create("user", "fury",
                auditAdminUnsignedCreds + ";s=signature", 0, auditAdminPrincipalAuthority);
        assertNotNull(rsrcAuditAdminPrince);
        ((SimplePrincipal) rsrcAuditAdminPrince).setUnsignedCreds(auditAdminUnsignedCreds);

        Mockito.when(mockDomRestRsrcCtx.principal()).thenReturn(rsrcAuditAdminPrince);
        Mockito.when(mockDomRsrcCtx.principal()).thenReturn(rsrcAuditAdminPrince);

        zms.putMembershipDecision(mockDomRsrcCtx, domainName, roleName, "user.bob", auditRef, mbr);

        //revert back to admin principal
        Authority adminPrincipalAuthority = new com.yahoo.athenz.common.server.debug.DebugPrincipalAuthority();
        String adminUnsignedCreds = "v=U1;d=user;n=user1";
        // used with the mockDomRestRsrcCtx
        final Principal rsrcAdminPrince = SimplePrincipal.create("user", "user1",
                adminUnsignedCreds + ";s=signature", 0, adminPrincipalAuthority);
        assertNotNull(rsrcAdminPrince);
        ((SimplePrincipal) rsrcAdminPrince).setUnsignedCreds(adminUnsignedCreds);

        Mockito.when(mockDomRestRsrcCtx.principal()).thenReturn(rsrcAdminPrince);
        Mockito.when(mockDomRsrcCtx.principal()).thenReturn(rsrcAdminPrince);

        resrole = zms.getRole(mockDomRsrcCtx, domainName, roleName, false, false, false);
        assertEquals(resrole.getRoleMembers().size(), 3);
        for (RoleMember rmem : resrole.getRoleMembers()) {
            if ("user.bob".equals(rmem.getMemberName())) {
                assertTrue(rmem.getApproved());
            }
        }

        clenaupPrincipalAuditedRoleApprovalByDomain(zms, domainName);
        zms.deleteTopLevelDomain(mockDomRsrcCtx, domainName, auditRef);
    }

    @Test
    public void testPutMembershipDecisionAuditEnabledRoleInvalidUser() {

        TopLevelDomain dom1 = createTopLevelDomainObject("testdomain1", "Approval Test Domain1",
                "testOrg", "user.user1");
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);
        DomainMeta meta = createDomainMetaObject("Domain Meta for approval test", "testOrg",
                true, true, "12345", 1001);
        zms.putDomainMeta(mockDomRsrcCtx, "testdomain1", auditRef, meta);
        zms.putDomainSystemMeta(mockDomRsrcCtx, "testdomain1", "auditenabled", auditRef, meta);

        Role auditedRole = createRoleObject("testdomain1", "testrole1", null, "user.john", "user.jane");
        zms.putRole(mockDomRsrcCtx, "testdomain1", "testrole1", auditRef, auditedRole);
        RoleSystemMeta rsm = createRoleSystemMetaObject(true);
        zms.putRoleSystemMeta(mockDomRsrcCtx, "testdomain1", "testrole1", "auditenabled", auditRef, rsm);

        Membership mbr = new Membership();
        mbr.setMemberName("user.joe");
        mbr.setActive(false);
        mbr.setApproved(false);
        zms.putMembership(mockDomRsrcCtx, "testdomain1", "testrole1", "user.joe", auditRef, mbr);

        mbr = new Membership();
        mbr.setMemberName("user.bob");
        mbr.setActive(false);
        mbr.setApproved(false);
        zms.putMembership(mockDomRsrcCtx, "testdomain1", "testrole1", "user.bob", auditRef, mbr);

        setupPrincipalAuditedRoleApprovalByOrg(zms, "user.fury", "testOrg");

        Authority auditAdminPrincipalAuthority = new com.yahoo.athenz.common.server.debug.DebugPrincipalAuthority();
        String auditAdminUnsignedCreds = "v=U1;d=user;n=fury";

        final Principal rsrcAuditAdminPrince = SimplePrincipal.create("user", "fury",
                auditAdminUnsignedCreds + ";s=signature", 0, auditAdminPrincipalAuthority);
        assertNotNull(rsrcAuditAdminPrince);
        ((SimplePrincipal) rsrcAuditAdminPrince).setUnsignedCreds(auditAdminUnsignedCreds);

        Mockito.when(mockDomRsrcCtx.principal()).thenReturn(rsrcAuditAdminPrince);

        // enable user authority check - joe and jane are the only
        // valid users in the system

        zms.userAuthority = new TestUserPrincipalAuthority();
        zms.validateUserRoleMembers = true;

        // first let's approve user.joe which should be ok since user joe
        // is a valid user based on our test authority

        mbr = new Membership();
        mbr.setMemberName("user.joe");
        mbr.setActive(true);
        mbr.setApproved(true);
        zms.putMembershipDecision(mockDomRsrcCtx, "testdomain1", "testrole1", "user.joe", auditRef, mbr);

        // now let's approve our bob user which is going to be rejected
        // since bob is not a valid user based on our test authority

        mbr.setMemberName("user.bob");

        try {
            zms.putMembershipDecision(mockDomRsrcCtx, "testdomain1", "testrole1", "user.bob", auditRef, mbr);
            fail();
        }catch (ResourceException ex) {
            assertEquals(ex.code, 400);
        }

        // now let's just reject user bob which should work
        // ok because we no longer validate users when we
        // are rejecting thus deleting role members

        mbr.setActive(false);
        mbr.setApproved(false);
        zms.putMembershipDecision(mockDomRsrcCtx, "testdomain1", "testrole1", "user.bob", auditRef, mbr);

        clenaupPrincipalAuditedRoleApprovalByOrg(zms, "testOrg");
        zms.deleteTopLevelDomain(mockDomRsrcCtx, "testdomain1", auditRef);
    }

    @Test
    public void testPutMembershipDecisionReviewEnabledUnauthorized() {

        final String domainName = "review-enabled-domain-forbidden";
        TopLevelDomain dom1 = createTopLevelDomainObject(domainName, "Approval test Domain1",
                "testOrg", "user.user1");
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        final String roleName = "review-role";
        Role role1 = createRoleObject(domainName, roleName, null, null, null);
        zms.putRole(mockDomRsrcCtx, domainName, roleName, auditRef, role1);

        RoleMeta rm = new RoleMeta().setReviewEnabled(true);
        zms.putRoleMeta(mockDomRsrcCtx, domainName, roleName, auditRef, rm);

        // add a user to the role

        Membership mbr = new Membership();
        mbr.setMemberName("user.bob");
        mbr.setActive(false);
        mbr.setApproved(false);

        zms.putMembership(mockDomRsrcCtx, domainName, roleName, "user.bob", auditRef, mbr);

        // verify the user is added with pending state

        Role resrole = zms.getRole(mockDomRsrcCtx, domainName, roleName, false, false, true);
        assertEquals(resrole.getRoleMembers().size(), 1);
        assertEquals(resrole.getRoleMembers().get(0).getMemberName(), "user.bob");
        assertFalse(resrole.getRoleMembers().get(0).getApproved());

        // now try as the second admin himself to approve this user and it must
        // be rejected since second admin is not authorized

        mbr = new Membership();
        mbr.setMemberName("user.bob");
        mbr.setActive(true);
        mbr.setApproved(true);

        // switch to user.user2 principal to add a member to a role

        Authority principalAuthority = new com.yahoo.athenz.common.server.debug.DebugPrincipalAuthority();
        String unsignedCreds = "v=U1;d=user;n=user2";
        final Principal rsrcPrince = SimplePrincipal.create("user", "user2",
                unsignedCreds + ";s=signature", 0, principalAuthority);
        assertNotNull(rsrcPrince);
        ((SimplePrincipal) rsrcPrince).setUnsignedCreds(unsignedCreds);
        Mockito.when(mockDomRestRsrcCtx.principal()).thenReturn(rsrcPrince);
        Mockito.when(mockDomRsrcCtx.principal()).thenReturn(rsrcPrince);

        try {
            zms.putMembershipDecision(mockDomRsrcCtx, domainName, roleName, "user.bob", auditRef, mbr);
            fail();
        } catch (ResourceException ex) {
            assertTrue(ex.getMessage().contains("not authorized to approve / reject members"));
        }

        // revert back to admin principal

        Authority adminPrincipalAuthority = new com.yahoo.athenz.common.server.debug.DebugPrincipalAuthority();
        String adminUnsignedCreds = "v=U1;d=user;n=user1";
        final Principal rsrcAdminPrince = SimplePrincipal.create("user", "user1",
                adminUnsignedCreds + ";s=signature", 0, adminPrincipalAuthority);
        assertNotNull(rsrcAdminPrince);
        ((SimplePrincipal) rsrcAdminPrince).setUnsignedCreds(adminUnsignedCreds);

        Mockito.when(mockDomRestRsrcCtx.principal()).thenReturn(rsrcAdminPrince);
        Mockito.when(mockDomRsrcCtx.principal()).thenReturn(rsrcAdminPrince);

        zms.deleteTopLevelDomain(mockDomRsrcCtx, domainName, auditRef);
    }

    @Test
    public void testPutMembershipDecisionErrors() {

        TopLevelDomain dom1 = createTopLevelDomainObject("testdomain1","Approval Test Domain1", "testOrg", "user.user1");
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);
        DomainMeta meta = createDomainMetaObject("Domain Meta for approval test", "testOrg",true, true, "12345", 1001);
        zms.putDomainMeta(mockDomRsrcCtx, "testdomain1", auditRef, meta);
        zms.putDomainSystemMeta(mockDomRsrcCtx, "testdomain1", "auditenabled", auditRef, meta);

        Role auditedRole = createRoleObject("testdomain1", "testrole1", null,"user.john", "user.jane");
        zms.putRole(mockDomRsrcCtx, "testdomain1", "testrole1", auditRef, auditedRole);
        RoleSystemMeta rsm = createRoleSystemMetaObject(true);
        zms.putRoleSystemMeta(mockDomRsrcCtx, "testdomain1", "testrole1", "auditenabled", auditRef, rsm);

        Membership mbr = new Membership();
        mbr.setMemberName("user.bob");
        mbr.setActive(false);
        mbr.setApproved(false);
        zms.putMembership(mockDomRsrcCtx, "testdomain1", "testrole1", "user.bob", auditRef, mbr);

        mbr = new Membership();
        mbr.setMemberName("user.bob");
        mbr.setActive(true);
        mbr.setApproved(true);

        try {
            zms.putMembershipDecision(mockDomRsrcCtx, "testdomain1", "testrole1", "user.chris", auditRef, mbr);//invalid member
            fail();
        } catch (ResourceException r) {
            assertEquals(r.code, 400);
            assertTrue(r.getMessage().contains("putMembershipDecision: Member name in URI and Membership object do not match"));
        }

        mbr.setRoleName("invalidrole");
        try {
            zms.putMembershipDecision(mockDomRsrcCtx, "testdomain1", "testrole1", "user.bob", auditRef, mbr);//invalid role
            fail();
        } catch (ResourceException r) {
            assertEquals(r.code, 400);
            assertTrue(r.getMessage().contains("putMembershipDecision: Role name in URI and Membership object do not match"));
        }

        mbr.setRoleName(null);
        try {
            zms.putMembershipDecision(mockDomRsrcCtx, "testdomain2", "testrole1", "user.bob", auditRef, mbr);//invalid domain name
            fail();
        } catch (ResourceException r) {
            assertEquals(r.code, 400);
            assertTrue(r.getMessage().contains("Invalid rolename"));
        }

        zms.deleteTopLevelDomain(mockDomRsrcCtx, "testdomain1", auditRef);
    }

    @Test
    public void testGetPendingDomainRoleMembersList() {

        TopLevelDomain dom1 = createTopLevelDomainObject("testdomain1", "Approval Test Domain1",
                "testOrg", "user.user1");
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        setupPrincipalAuditedRoleApprovalByOrg(zms, "user.fury", "testorg");

        DomainMeta meta = createDomainMetaObject("Domain Meta for approval test", "testorg",
                true, true, "12345", 1001);
        zms.putDomainMeta(mockDomRsrcCtx, "testdomain1", auditRef, meta);
        zms.putDomainSystemMeta(mockDomRsrcCtx, "testdomain1", "auditenabled", auditRef, meta);
        setupPrincipalSystemMetaDelete(zms, mockDomRsrcCtx.principal().getFullName(), "testdomain1", "org");
        zms.putDomainSystemMeta(mockDomRsrcCtx, "testdomain1", "org", auditRef, meta);

        Role auditedRole = createRoleObject("testdomain1", "testrole1", null, "user.john", "user.jane");
        zms.putRole(mockDomRsrcCtx, "testdomain1", "testrole1", auditRef, auditedRole);
        RoleSystemMeta rsm = createRoleSystemMetaObject(true);
        zms.putRoleSystemMeta(mockDomRsrcCtx, "testdomain1", "testrole1", "auditenabled", auditRef, rsm);

        Membership mbr = new Membership();
        mbr.setMemberName("user.bob");
        mbr.setActive(false);
        mbr.setApproved(false);
        zms.putMembership(mockDomRsrcCtx, "testdomain1", "testrole1", "user.bob", auditRef, mbr);

        mbr = new Membership();
        mbr.setMemberName("user.bob");
        mbr.setActive(true);
        mbr.setApproved(true);

        try {
            zms.putMembershipDecision(mockDomRsrcCtx, "testdomain1", "testrole1", "user.bob", auditRef, mbr);
            fail();
        } catch (ResourceException r) {
            assertEquals(r.code, 403);
        }

        // first request using specific principal

        DomainRoleMembership domainRoleMembership = zms.getPendingDomainRoleMembersList(mockDomRsrcCtx, "user.fury");

        assertNotNull(domainRoleMembership);
        assertNotNull(domainRoleMembership.getDomainRoleMembersList());
        assertEquals(domainRoleMembership.getDomainRoleMembersList().size(), 1);
        for (DomainRoleMembers drm : domainRoleMembership.getDomainRoleMembersList()) {
            assertEquals(drm.getDomainName(), "testdomain1");
            assertNotNull(drm.getMembers());
            for (DomainRoleMember mem : drm.getMembers()) {
                assertNotNull(mem);
                assertEquals(mem.getMemberName(), "user.bob");
                for (MemberRole mr : mem.getMemberRoles()) {
                    assertNotNull(mr);
                    assertEquals(mr.getRoleName(), "testrole1");
                }
            }
        }

        // repeat the request using context principal

        Principal mockPrincipal = Mockito.mock(Principal.class);
        Mockito.when(mockDomRsrcCtx.principal()).thenReturn(mockPrincipal);
        Mockito.when(mockPrincipal.getDomain()).thenReturn("user");
        Mockito.when(mockPrincipal.getFullName()).thenReturn("user.fury");
        domainRoleMembership = zms.getPendingDomainRoleMembersList(mockDomRsrcCtx, null);

        assertNotNull(domainRoleMembership);
        assertNotNull(domainRoleMembership.getDomainRoleMembersList());
        assertEquals(domainRoleMembership.getDomainRoleMembersList().size(), 1);
        for (DomainRoleMembers drm : domainRoleMembership.getDomainRoleMembersList()) {
            assertEquals(drm.getDomainName(), "testdomain1");
            assertNotNull(drm.getMembers());
            for (DomainRoleMember mem : drm.getMembers()) {
                assertNotNull(mem);
                assertEquals(mem.getMemberName(), "user.bob");
                for (MemberRole mr : mem.getMemberRoles()) {
                    assertNotNull(mr);
                    assertEquals(mr.getRoleName(), "testrole1");
                }
            }
        }

        cleanupPrincipalSystemMetaDelete(zms);
        clenaupPrincipalAuditedRoleApprovalByOrg(zms, "testOrg");

        zms.deleteTopLevelDomain(mockDomRsrcCtx, "testdomain1", auditRef);
    }

    @Test
    public void testGetRoleWithPendingMembers() {

        TopLevelDomain dom1 = createTopLevelDomainObject("testdomain1","Pending Test Domain1",
                "testOrg", "user.user1");
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        DomainMeta meta = createDomainMetaObject("Domain Meta for approval test", "testOrg",
                true, true, "12345", 1001);
        zms.putDomainMeta(mockDomRsrcCtx, "testdomain1", auditRef, meta);
        zms.putDomainSystemMeta(mockDomRsrcCtx, "testdomain1", "auditenabled", auditRef, meta);

        Role auditedRole = createRoleObject("testdomain1", "testrole1", null,"user.john", "user.jane");
        zms.putRole(mockDomRsrcCtx, "testdomain1", "testrole1", auditRef, auditedRole);
        RoleSystemMeta rsm = createRoleSystemMetaObject(true);
        zms.putRoleSystemMeta(mockDomRsrcCtx, "testdomain1", "testrole1", "auditenabled", auditRef, rsm);

        Membership mbr = new Membership();
        mbr.setMemberName("user.bob");
        mbr.setActive(false);
        mbr.setApproved(false);
        zms.putMembership(mockDomRsrcCtx, "testdomain1", "testrole1", "user.bob", auditRef, mbr);

        Role resRole = zms.getRole(mockDomRsrcCtx, "testdomain1", "testrole1", false, false, true);

        assertNotNull(resRole);
        assertEquals(resRole.getRoleMembers().size(), 3);

        for ( RoleMember rm : resRole.getRoleMembers()) {
            if ("user.bob".equals(rm.getMemberName())) {
                assertFalse(rm.getApproved());
            }
        }

        zms.deleteTopLevelDomain(mockDomRsrcCtx, "testdomain1", auditRef);
    }

    @Test
    public void testValidateRoleMemberPrincipals() {

        zms.validateUserRoleMembers = false;
        zms.validateServiceRoleMembers = false;

        // if both are false then any invalid users are ok

        List<RoleMember> roleMembers = new ArrayList<>();
        roleMembers.add(new RoleMember().setMemberName("user"));
        roleMembers.add(new RoleMember().setMemberName("user.john"));
        roleMembers.add(new RoleMember().setMemberName("user.jane"));
        roleMembers.add(new RoleMember().setMemberName("coretech.api"));
        roleMembers.add(new RoleMember().setMemberName("coretech.backend"));

        Role role = new Role().setRoleMembers(roleMembers);
        zms.validateRoleMemberPrincipals(role, null, "unittest");

        // enable user authority check

        zms.userAuthority = new TestUserPrincipalAuthority();
        zms.validateUserRoleMembers = true;

        // include all valid principals

        roleMembers = new ArrayList<>();
        roleMembers.add(new RoleMember().setMemberName("user.joe"));
        roleMembers.add(new RoleMember().setMemberName("user.jane"));
        role.setRoleMembers(roleMembers);

        zms.validateRoleMemberPrincipals(role, null, "unittest");

        // add one more invalid user

        roleMembers.add(new RoleMember().setMemberName("user.john"));
        try {
            zms.validateRoleMemberPrincipals(role, null, "unittest");
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), ResourceException.BAD_REQUEST);
        }
    }

    @Test
    public void testValidateRoleMemberPrincipalUser() {

        zms.userAuthority = new TestUserPrincipalAuthority();
        zms.validateUserRoleMembers = true;

        // valid users no exception

        zms.validateRoleMemberPrincipal("user.joe", null, "unittest");
        zms.validateRoleMemberPrincipal("user.jane", null, "unittest");

        // invalid user request error

        try {
            zms.validateRoleMemberPrincipal("user.john", null, "unittest");
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), ResourceException.BAD_REQUEST);
        }

        // non - user principals by default are accepted

        zms.validateRoleMemberPrincipal("coretech.api", null, "unittest");

        // valid employee and contractor users

        zms.validateRoleMemberPrincipal("user.joe", "employee", "unittest");
        zms.validateRoleMemberPrincipal("user.jane", "employee", "unittest");
        zms.validateRoleMemberPrincipal("user.jack", "contractor", "unittest");

        // valid multiple attribute users

        zms.validateRoleMemberPrincipal("user.joe", "employee,local", "unittest");
        zms.validateRoleMemberPrincipal("user.jane", "employee,local", "unittest");
        zms.validateRoleMemberPrincipal("user.jack", "contractor,local", "unittest");

        // invalid employee type

        try {
            zms.validateRoleMemberPrincipal("user.jack", "employee", "unittest");
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), ResourceException.BAD_REQUEST);
        }

        // invalid multiple types

        try {
            zms.validateRoleMemberPrincipal("user.jack", "local,employee", "unittest");
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), ResourceException.BAD_REQUEST);
        }
    }

    @Test
    public void testValidateRoleMemberPrincipalService() {

        zms.validateServiceRoleMembers = true;

        // wildcards are always valid with no exception

        zms.validateRoleMemberPrincipal("athenz.api*", null, "unittest");
        zms.validateRoleMemberPrincipal("coretech.*", null, "unittest");

        // should get back invalid request since service does not exist

        try {
            zms.validateRoleMemberPrincipal("coretech.api", "employee", "unittest");
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), ResourceException.BAD_REQUEST);
        }

        // invalid service request error

        try {
            zms.validateRoleMemberPrincipal("coretech", null, "unittest");
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), ResourceException.BAD_REQUEST);
        }

        TopLevelDomain dom1 = createTopLevelDomainObject("coretech",
                "Test Domain1", "testorg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        TopLevelDomain dom2 = createTopLevelDomainObject("coretech2",
                "Test Domain2", "testorg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom2);

        ServiceIdentity service1 = createServiceObject("coretech",
                "api", "http://localhost", "/usr/bin/java", "root",
                "users", "host1");

        zms.putServiceIdentity(mockDomRsrcCtx, "coretech", "api", auditRef, service1);

        // known service - no exception

        zms.validateRoleMemberPrincipal("coretech.api", null, "unittest");

        // unknown service - exception

        try {
            zms.validateRoleMemberPrincipal("coretech.backend", null, "unittest");
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), ResourceException.BAD_REQUEST);
        }

        // include coretech in the skip domain list and try
        // the operation again

        System.setProperty(ZMSConsts.ZMS_PROP_VALIDATE_SERVICE_MEMBERS_SKIP_DOMAINS,
                "unix,coretech");
        zms.loadConfigurationSettings();
        zms.validateServiceRoleMembers = true;

        // coretech is now accepted

        zms.validateRoleMemberPrincipal("coretech.backend", null, "unittest");

        // but coretech2 is rejected

        try {
            zms.validateRoleMemberPrincipal("coretech2.backend", null, "unittest");
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), ResourceException.BAD_REQUEST);
        }

        // user principals by default are accepted

        zms.validateRoleMemberPrincipal("user.john", null, "unittest");

        // reset our setting

        System.clearProperty(ZMSConsts.ZMS_PROP_VALIDATE_SERVICE_MEMBERS_SKIP_DOMAINS);
        zms.deleteTopLevelDomain(mockDomRsrcCtx, "coretech", auditRef);
        zms.deleteTopLevelDomain(mockDomRsrcCtx, "coretech2", auditRef);
    }

    @Test
    public void testCreateMembershipApprovalNotification() {

        TopLevelDomain dom1 = createTopLevelDomainObject("testdomain1","Role Test Domain1", "testOrg", "user.user1");
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        Role selfserverole = createRoleObject("testdomain1", "testrole2", null,"user.john", "user.jane");
        zms.putRole(mockDomRsrcCtx, "testdomain1", "testrole2", auditRef, selfserverole);

        RoleMeta rm = createRoleMetaObject(true);
        zms.putRoleMeta(mockDomRsrcCtx, "testdomain1", "testrole2",  auditRef, rm);

        Authority auditAdminPrincipalAuthority = new com.yahoo.athenz.common.server.debug.DebugPrincipalAuthority();
        String auditAdminUnsignedCreds = "v=U1;d=user;n=fury";
        // used with the mockDomRestRsrcCtx
        final Principal rsrcAuditAdminPrince = SimplePrincipal.create("user", "fury",
                auditAdminUnsignedCreds + ";s=signature", 0, auditAdminPrincipalAuthority);
        assertNotNull(rsrcAuditAdminPrince);
        ((SimplePrincipal) rsrcAuditAdminPrince).setUnsignedCreds(auditAdminUnsignedCreds);

        Mockito.when(mockDomRestRsrcCtx.principal()).thenReturn(rsrcAuditAdminPrince);
        Mockito.when(mockDomRsrcCtx.principal()).thenReturn(rsrcAuditAdminPrince);

        Membership membership = new Membership().setActive(false).setApproved(false)
                .setMemberName("user.fury").setRoleName("testrole2");

        zms.putMembership(mockDomRsrcCtx, "testdomain1", "testrole2", "user.fury", "adding fury", membership);

        //revert back to admin principal
        Authority adminPrincipalAuthority = new com.yahoo.athenz.common.server.debug.DebugPrincipalAuthority();
        String adminUnsignedCreds = "v=U1;d=user;n=user1";
        // used with the mockDomRestRsrcCtx
        final Principal rsrcAdminPrince = SimplePrincipal.create("user", "user1",
                adminUnsignedCreds + ";s=signature", 0, adminPrincipalAuthority);
        assertNotNull(rsrcAdminPrince);
        ((SimplePrincipal) rsrcAdminPrince).setUnsignedCreds(adminUnsignedCreds);

        Mockito.when(mockDomRestRsrcCtx.principal()).thenReturn(rsrcAdminPrince);
        Mockito.when(mockDomRsrcCtx.principal()).thenReturn(rsrcAdminPrince);

        List<Notification> expextedNotifications = Collections.singletonList(new Notification());
        expextedNotifications.get(0).addRecipient("user.user1");
        expextedNotifications.get(0).addDetails("requester", "user.fury");
        expextedNotifications.get(0).addDetails("reason", "adding fury");
        expextedNotifications.get(0).addDetails("role", "testrole2");
        expextedNotifications.get(0).addDetails("domain", "testdomain1");
        expextedNotifications.get(0).addDetails("member", "user.fury");
        expextedNotifications.get(0).setNotificationToEmailConverter(new PutRoleMembershipNotificationTask.PutMembershipNotificationToEmailConverter());

        Mockito.verify(mockNotificationManager,
                times(1)).sendNotifications(eq(expextedNotifications));

        zms.deleteTopLevelDomain(mockDomRsrcCtx, "testdomain1", auditRef);
    }

    private boolean validateDueDate(long millis, long extMillis) {
        return (millis > System.currentTimeMillis() + extMillis - 5000 && millis < System.currentTimeMillis() + extMillis + 5000);
    }

    @Test
    public void testConfiguredExpiryMillis() {

        assertEquals(zms.configuredDueDateMillis(null, null), 0);
        assertEquals(zms.configuredDueDateMillis(null, -3), 0);
        assertEquals(zms.configuredDueDateMillis(null, 0), 0);
        assertEquals(zms.configuredDueDateMillis(-3, null), 0);
        assertEquals(zms.configuredDueDateMillis(0, null), 0);
        assertEquals(zms.configuredDueDateMillis(-3, -3), 0);
        assertEquals(zms.configuredDueDateMillis(0, 0), 0);

        long extMillis = TimeUnit.MILLISECONDS.convert(10, TimeUnit.DAYS);
        long millis = zms.configuredDueDateMillis(null, 10);
        assertTrue(validateDueDate(millis, extMillis));
        millis = zms.configuredDueDateMillis(null, 10);
        assertTrue(validateDueDate(millis, extMillis));
        millis = zms.configuredDueDateMillis(-1, 10);
        assertTrue(validateDueDate(millis, extMillis));
        millis = zms.configuredDueDateMillis(0, 10);
        assertTrue(validateDueDate(millis, extMillis));
        millis = zms.configuredDueDateMillis(5, 10);
        assertTrue(validateDueDate(millis, extMillis));
        millis = zms.configuredDueDateMillis(20, 10);
        assertTrue(validateDueDate(millis, extMillis));

        millis = zms.configuredDueDateMillis(10, null);
        assertTrue(validateDueDate(millis, extMillis));
        millis = zms.configuredDueDateMillis(10, -1);
        assertTrue(validateDueDate(millis, extMillis));
        millis = zms.configuredDueDateMillis(10, 0);
        assertTrue(validateDueDate(millis, extMillis));
    }

    @Test
    public void testGetMemberDueDate() {
        assertEquals(zms.getMemberDueDate(100, null), Timestamp.fromMillis(100));
        assertEquals(zms.getMemberDueDate(100, Timestamp.fromMillis(50)), Timestamp.fromMillis(50));
        assertEquals(zms.getMemberDueDate(100, Timestamp.fromMillis(150)), Timestamp.fromMillis(100));
    }

    @Test
    public void testMemberDueDateTimestamp() {
        assertEquals(zms.memberDueDateTimestamp(null, null, Timestamp.fromMillis(100)), Timestamp.fromMillis(100));
        assertEquals(zms.memberDueDateTimestamp(-1, 0, Timestamp.fromMillis(100)), Timestamp.fromMillis(100));
        assertEquals(zms.memberDueDateTimestamp(-3, -2, Timestamp.fromMillis(100)), Timestamp.fromMillis(100));

        long ext50Millis = TimeUnit.MILLISECONDS.convert(50, TimeUnit.DAYS);
        long ext75Millis = TimeUnit.MILLISECONDS.convert(75, TimeUnit.DAYS);
        long ext100Millis = TimeUnit.MILLISECONDS.convert(100, TimeUnit.DAYS);

        Timestamp stamp = zms.memberDueDateTimestamp(100, 50, Timestamp.fromMillis(System.currentTimeMillis() + ext75Millis));
        assertTrue(validateDueDate(stamp.millis(), ext50Millis));

        stamp = zms.memberDueDateTimestamp(75, null, Timestamp.fromMillis(System.currentTimeMillis() + ext100Millis));
        assertTrue(validateDueDate(stamp.millis(), ext75Millis));
    }

    @Test
    public void testUpdateRoleMemberReview() {

        long ext100Millis = TimeUnit.MILLISECONDS.convert(100, TimeUnit.DAYS);
        long ext125Millis = TimeUnit.MILLISECONDS.convert(125, TimeUnit.DAYS);
        long ext150Millis = TimeUnit.MILLISECONDS.convert(150, TimeUnit.DAYS);

        List<RoleMember> members = new ArrayList<>();
        members.add(new RoleMember().setMemberName("user.joe").setReviewReminder(null));
        members.add(new RoleMember().setMemberName("user.jane")
                .setReviewReminder(Timestamp.fromMillis(System.currentTimeMillis() + ext100Millis)));
        members.add(new RoleMember().setMemberName("athenz.api").setReviewReminder(null));
        members.add(new RoleMember().setMemberName("athenz.backend")
                .setReviewReminder(Timestamp.fromMillis(System.currentTimeMillis() + ext100Millis)));

        zms.updateRoleMemberReviewReminder(
                125,
                150, members);

        Timestamp stamp = members.get(0).getReviewReminder();
        assertTrue(validateDueDate(stamp.millis(), ext125Millis));

        stamp = members.get(1).getReviewReminder();
        assertTrue(validateDueDate(stamp.millis(), ext100Millis));

        stamp = members.get(2).getReviewReminder();
        assertTrue(validateDueDate(stamp.millis(), ext150Millis));

        stamp = members.get(3).getReviewReminder();
        assertTrue(validateDueDate(stamp.millis(), ext100Millis));
    }

    @Test
    public void testUpdateRoleMemberReviewNoUser() {

        long ext100Millis = TimeUnit.MILLISECONDS.convert(100, TimeUnit.DAYS);
        long ext150Millis = TimeUnit.MILLISECONDS.convert(150, TimeUnit.DAYS);

        List<RoleMember> members = new ArrayList<>();
        members.add(new RoleMember().setMemberName("user.joe").setReviewReminder(null));
        members.add(new RoleMember().setMemberName("user.jane")
                .setReviewReminder(Timestamp.fromMillis(System.currentTimeMillis() + ext100Millis)));
        members.add(new RoleMember().setMemberName("athenz.api").setReviewReminder(null));
        members.add(new RoleMember().setMemberName("athenz.backend")
                .setReviewReminder(Timestamp.fromMillis(System.currentTimeMillis() + ext100Millis)));

        zms.updateRoleMemberReviewReminder(
                0,
                150,
                members);

        assertNull(members.get(0).getReviewReminder());

        Timestamp stamp = members.get(1).getReviewReminder();
        assertTrue(validateDueDate(stamp.millis(), ext100Millis));

        stamp = members.get(2).getReviewReminder();
        assertTrue(validateDueDate(stamp.millis(), ext150Millis));

        stamp = members.get(3).getReviewReminder();
        assertTrue(validateDueDate(stamp.millis(), ext100Millis));
    }

    @Test
    public void testUpdateRoleMemberReviewNoService() {

        long ext100Millis = TimeUnit.MILLISECONDS.convert(100, TimeUnit.DAYS);
        long ext125Millis = TimeUnit.MILLISECONDS.convert(125, TimeUnit.DAYS);

        List<RoleMember> members = new ArrayList<>();
        members.add(new RoleMember().setMemberName("user.joe").setReviewReminder(null));
        members.add(new RoleMember().setMemberName("user.jane")
                .setReviewReminder(Timestamp.fromMillis(System.currentTimeMillis() + ext100Millis)));
        members.add(new RoleMember().setMemberName("athenz.api").setReviewReminder(null));
        members.add(new RoleMember().setMemberName("athenz.backend")
                .setReviewReminder(Timestamp.fromMillis(System.currentTimeMillis() + ext100Millis)));

        zms.updateRoleMemberReviewReminder(125, 0, members);

        Timestamp stamp = members.get(0).getReviewReminder();
        assertTrue(validateDueDate(stamp.millis(), ext125Millis));

        stamp = members.get(1).getReviewReminder();
        assertTrue(validateDueDate(stamp.millis(), ext100Millis));

        assertNull(members.get(2).getReviewReminder());

        stamp = members.get(3).getReviewReminder();
        assertTrue(validateDueDate(stamp.millis(), ext100Millis));
    }

    @Test
    public void testUpdateRoleMemberExpiration() {

        long ext100Millis = TimeUnit.MILLISECONDS.convert(100, TimeUnit.DAYS);
        long ext125Millis = TimeUnit.MILLISECONDS.convert(125, TimeUnit.DAYS);
        long ext150Millis = TimeUnit.MILLISECONDS.convert(150, TimeUnit.DAYS);

        List<RoleMember> members = new ArrayList<>();
        members.add(new RoleMember().setMemberName("user.joe").setExpiration(null));
        members.add(new RoleMember().setMemberName("user.jane")
                .setExpiration(Timestamp.fromMillis(System.currentTimeMillis() + ext100Millis)));
        members.add(new RoleMember().setMemberName("athenz.api").setExpiration(null));
        members.add(new RoleMember().setMemberName("athenz.backend")
                .setExpiration(Timestamp.fromMillis(System.currentTimeMillis() + ext100Millis)));

        // for user members we have 50/125 setup while for service members 75/150

        zms.updateRoleMemberExpiration(
                50,
                125,
                75,
                150, members);

        Timestamp stamp = members.get(0).getExpiration();
        assertTrue(validateDueDate(stamp.millis(), ext125Millis));

        stamp = members.get(1).getExpiration();
        assertTrue(validateDueDate(stamp.millis(), ext100Millis));

        stamp = members.get(2).getExpiration();
        assertTrue(validateDueDate(stamp.millis(), ext150Millis));

        stamp = members.get(3).getExpiration();
        assertTrue(validateDueDate(stamp.millis(), ext100Millis));
    }

    @Test
    public void testUpdateRoleMemberExpirationNoUser() {

        long ext100Millis = TimeUnit.MILLISECONDS.convert(100, TimeUnit.DAYS);
        long ext150Millis = TimeUnit.MILLISECONDS.convert(150, TimeUnit.DAYS);

        List<RoleMember> members = new ArrayList<>();
        members.add(new RoleMember().setMemberName("user.joe").setExpiration(null));
        members.add(new RoleMember().setMemberName("user.jane")
                .setExpiration(Timestamp.fromMillis(System.currentTimeMillis() + ext100Millis)));
        members.add(new RoleMember().setMemberName("athenz.api").setExpiration(null));
        members.add(new RoleMember().setMemberName("athenz.backend")
                .setExpiration(Timestamp.fromMillis(System.currentTimeMillis() + ext100Millis)));

        // for user members we have 0 setup while for service members 75/150

        zms.updateRoleMemberExpiration(
                0,
                0,
                75,
                150,
                members);

        assertNull(members.get(0).getExpiration());

        Timestamp stamp = members.get(1).getExpiration();
        assertTrue(validateDueDate(stamp.millis(), ext100Millis));

        stamp = members.get(2).getExpiration();
        assertTrue(validateDueDate(stamp.millis(), ext150Millis));

        stamp = members.get(3).getExpiration();
        assertTrue(validateDueDate(stamp.millis(), ext100Millis));
    }

    @Test
    public void testUpdateRoleMemberExpirationNoService() {

        long ext100Millis = TimeUnit.MILLISECONDS.convert(100, TimeUnit.DAYS);
        long ext125Millis = TimeUnit.MILLISECONDS.convert(125, TimeUnit.DAYS);

        List<RoleMember> members = new ArrayList<>();
        members.add(new RoleMember().setMemberName("user.joe").setExpiration(null));
        members.add(new RoleMember().setMemberName("user.jane")
                .setExpiration(Timestamp.fromMillis(System.currentTimeMillis() + ext100Millis)));
        members.add(new RoleMember().setMemberName("athenz.api").setExpiration(null));
        members.add(new RoleMember().setMemberName("athenz.backend")
                .setExpiration(Timestamp.fromMillis(System.currentTimeMillis() + ext100Millis)));

        // for user members we have 50/125 setup while for service members 0

        zms.updateRoleMemberExpiration(
                50,
                125,
                0,
                0,
                members);

        Timestamp stamp = members.get(0).getExpiration();
        assertTrue(validateDueDate(stamp.millis(), ext125Millis));

        stamp = members.get(1).getExpiration();
        assertTrue(validateDueDate(stamp.millis(), ext100Millis));

        assertNull(members.get(2).getExpiration());

        stamp = members.get(3).getExpiration();
        assertTrue(validateDueDate(stamp.millis(), ext100Millis));
    }

    @Test
    public void testRemoveMatchedAssertionNoMatch() {

        List<Assertion> assertions = new ArrayList<>();
        List<Assertion> matchedAssertions = new ArrayList<>();

        Assertion assertion = new Assertion().setAction("action").setResource("resource")
                .setRole("role").setEffect(AssertionEffect.ALLOW);
        assertions.add(assertion);

        Assertion checkAssertion = new Assertion().setAction("action").setResource("resource")
                .setRole("role1").setEffect(AssertionEffect.ALLOW);

        assertFalse(zms.dbService.removeMatchedAssertion(checkAssertion, assertions, matchedAssertions));

        // match the role but not the affect

        checkAssertion.setRole("role");
        checkAssertion.setEffect(AssertionEffect.DENY);

        assertFalse(zms.dbService.removeMatchedAssertion(checkAssertion, assertions, matchedAssertions));

        // full match

        checkAssertion.setEffect(AssertionEffect.ALLOW);
        assertTrue(zms.dbService.removeMatchedAssertion(checkAssertion, assertions, matchedAssertions));
    }

    @Test
    public void testIsUserDomainPrincipal() {

        // default no additional user domains

        ZMSImpl zmsImpl = zmsInit();
        assertTrue(ZMSUtils.isUserDomainPrincipal("user.joe", zmsImpl.userDomainPrefix, zmsImpl.addlUserCheckDomainPrefixList));
        assertFalse(ZMSUtils.isUserDomainPrincipal("unix.joe", zmsImpl.userDomainPrefix, zmsImpl.addlUserCheckDomainPrefixList));
        assertFalse(ZMSUtils.isUserDomainPrincipal("ldap.joe", zmsImpl.userDomainPrefix, zmsImpl.addlUserCheckDomainPrefixList));
        assertFalse(ZMSUtils.isUserDomainPrincipal("x509.joe", zmsImpl.userDomainPrefix, zmsImpl.addlUserCheckDomainPrefixList));

        // now let's set the addls to empty - no changes

        System.setProperty(ZMSConsts.ZMS_PROP_ADDL_USER_CHECK_DOMAINS, "");
        zmsImpl = zmsInit();
        assertTrue(ZMSUtils.isUserDomainPrincipal("user.joe", zmsImpl.userDomainPrefix, zmsImpl.addlUserCheckDomainPrefixList));
        assertFalse(ZMSUtils.isUserDomainPrincipal("unix.joe", zmsImpl.userDomainPrefix, zmsImpl.addlUserCheckDomainPrefixList));
        assertFalse(ZMSUtils.isUserDomainPrincipal("ldap.joe", zmsImpl.userDomainPrefix, zmsImpl.addlUserCheckDomainPrefixList));
        assertFalse(ZMSUtils.isUserDomainPrincipal("x509.joe", zmsImpl.userDomainPrefix, zmsImpl.addlUserCheckDomainPrefixList));

        // now let's add one of the domains to the list

        System.setProperty(ZMSConsts.ZMS_PROP_ADDL_USER_CHECK_DOMAINS, "unix");
        zmsImpl = zmsInit();
        assertTrue(ZMSUtils.isUserDomainPrincipal("user.joe", zmsImpl.userDomainPrefix, zmsImpl.addlUserCheckDomainPrefixList));
        assertTrue(ZMSUtils.isUserDomainPrincipal("unix.joe", zmsImpl.userDomainPrefix, zmsImpl.addlUserCheckDomainPrefixList));
        assertFalse(ZMSUtils.isUserDomainPrincipal("ldap.joe", zmsImpl.userDomainPrefix, zmsImpl.addlUserCheckDomainPrefixList));
        assertFalse(ZMSUtils.isUserDomainPrincipal("x509.joe", zmsImpl.userDomainPrefix, zmsImpl.addlUserCheckDomainPrefixList));

        // now let's set two domains in the list

        System.setProperty(ZMSConsts.ZMS_PROP_ADDL_USER_CHECK_DOMAINS, "unix,ldap");
        zmsImpl = zmsInit();
        assertTrue(ZMSUtils.isUserDomainPrincipal("user.joe", zmsImpl.userDomainPrefix, zmsImpl.addlUserCheckDomainPrefixList));
        assertTrue(ZMSUtils.isUserDomainPrincipal("unix.joe", zmsImpl.userDomainPrefix, zmsImpl.addlUserCheckDomainPrefixList));
        assertTrue(ZMSUtils.isUserDomainPrincipal("ldap.joe", zmsImpl.userDomainPrefix, zmsImpl.addlUserCheckDomainPrefixList));
        assertFalse(ZMSUtils.isUserDomainPrincipal("x509.joe", zmsImpl.userDomainPrefix, zmsImpl.addlUserCheckDomainPrefixList));

        System.clearProperty(ZMSConsts.ZMS_PROP_ADDL_USER_CHECK_DOMAINS);
        zmsImpl.objectStore.clearConnections();
    }

    @Test
    public void testPutRoleReviewEnabledMembers() {

        final String domainName = "role-review-enabled";
        TopLevelDomain dom1 = createTopLevelDomainObject(domainName,
                "Role review Test Domain1", "testOrg", "user.user1");
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        Role role1 = createRoleObject(domainName, "role1", null, "user.john", "user.jane");
        role1.setReviewEnabled(true);

        try {
            zms.putRole(mockDomRsrcCtx, domainName, "role1", auditRef, role1);
            fail();
        } catch (ResourceException ex) {
            assertTrue(ex.getMessage().contains("Set review enabled flag using role meta api"));
        }

        // now create a role review enabled with no members

        Role role2 = createRoleObject(domainName, "role2", null, null, null);
        role2.setReviewEnabled(true);
        zms.putRole(mockDomRsrcCtx, domainName, "role2", auditRef, role2);

        Role resRole2 = zms.getRole(mockDomRsrcCtx, domainName, "role2", false, false, false);
        assertNotNull(resRole2);
        assertTrue(resRole2.getReviewEnabled());

        // we should not be able to modify a review enabled role

        Role role2a = createRoleObject(domainName, "role2", null, "user.john", "user.jane");
        try {
            zms.putRole(mockDomRsrcCtx, domainName, "role2", auditRef, role2a);
            fail();
        } catch (ResourceException ex) {
            assertTrue(ex.getMessage().contains("Can not update auditEnabled and/or reviewEnabled roles"));
        }

        zms.deleteTopLevelDomain(mockDomRsrcCtx, domainName, auditRef);
    }

    @Test
    public void testPutRoleReview() {

        TopLevelDomain dom1 = createTopLevelDomainObject("role-review-dom",
                "Role review Test Domain1", "testOrg", "user.user1");
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        Role role1 = createRoleObject("role-review-dom", "role1", null,
                "user.john", "user.jane");
        zms.putRole(mockDomRsrcCtx, "role-review-dom", "role1", auditRef, role1);

        Timestamp tenDaysExpiry = Timestamp.fromMillis(System.currentTimeMillis() + TimeUnit.MILLISECONDS.convert(10, TimeUnit.DAYS));
        Timestamp sixtyDaysExpiry = Timestamp.fromMillis(System.currentTimeMillis() + TimeUnit.MILLISECONDS.convert(60, TimeUnit.DAYS));
        Timestamp fortyFiveDaysLowerBoundExpiry = Timestamp.fromMillis(System.currentTimeMillis() + TimeUnit.MILLISECONDS.convert(45, TimeUnit.DAYS));

        Membership mbr = generateMembership("role1", "user.doe", tenDaysExpiry);
        zms.putMembership(mockDomRsrcCtx, "role-review-dom", "role1", "user.doe", auditRef, mbr);

        RoleMeta rm = createRoleMetaObject(true);
        rm.setMemberExpiryDays(45);
        zms.putRoleMeta(mockDomRsrcCtx, "role-review-dom", "role1", auditRef, rm);

        Role inputRole = new Role().setName("role1");
        List<RoleMember> inputMembers = new ArrayList<>();
        inputRole.setRoleMembers(inputMembers);
        inputMembers.add(new RoleMember().setMemberName("user.john").setActive(false));
        inputMembers.add(new RoleMember().setMemberName("user.doe").setActive(true).setExpiration(sixtyDaysExpiry));
        zms.putRoleReview(mockDomRsrcCtx, "role-review-dom", "role1", auditRef, inputRole);

        Role resRole1 = zms.getRole(mockDomRsrcCtx, "role-review-dom", "role1", false, false, false);

        Timestamp fortyFiveDaysUpperBoundExpiry = Timestamp.fromMillis(System.currentTimeMillis() + TimeUnit.MILLISECONDS.convert(45, TimeUnit.DAYS) + TimeUnit.MILLISECONDS.convert(5, TimeUnit.MINUTES));

        int userChecked = 0;
        for (RoleMember roleMember : resRole1.getRoleMembers()) {
            if (roleMember.getMemberName().equals("user.jane") || roleMember.getMemberName().equals("user.doe")) {
                userChecked += 1;
                assertTrue(roleMember.getExpiration().toDate().after(fortyFiveDaysLowerBoundExpiry.toDate()) && roleMember.getExpiration().toDate().before(fortyFiveDaysUpperBoundExpiry.toDate()));
                assertTrue(roleMember.getApproved());
            }
        }
        assertEquals(userChecked, 2);
        zms.deleteTopLevelDomain(mockDomRsrcCtx, "role-review-dom", auditRef);
    }

    @Test
    public void testPutRoleReviewError() {

        TopLevelDomain dom1 = createTopLevelDomainObject("role-review-dom",
                "Role review Test Domain1", "testOrg", "user.user1");
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        Role role1 = createRoleObject("role-review-dom", "role1", null,
                "user.john", "user.jane");
        zms.putRole(mockDomRsrcCtx, "role-review-dom", "role1", auditRef, role1);

        Timestamp tenDaysExpiry = Timestamp.fromMillis(System.currentTimeMillis() + TimeUnit.MILLISECONDS.convert(10, TimeUnit.DAYS));
        Timestamp sixtyDaysExpiry = Timestamp.fromMillis(System.currentTimeMillis() + TimeUnit.MILLISECONDS.convert(60, TimeUnit.DAYS));

        Membership mbr = generateMembership("role1", "user.doe", tenDaysExpiry);
        zms.putMembership(mockDomRsrcCtx, "role-review-dom", "role1", "user.doe", auditRef, mbr);

        Role inputRole = new Role().setName("role1");
        List<RoleMember> inputMembers = new ArrayList<>();
        inputRole.setRoleMembers(inputMembers);
        inputMembers.add(new RoleMember().setMemberName("user.john").setActive(false));
        inputMembers.add(new RoleMember().setMemberName("user.doe").setActive(true).setExpiration(sixtyDaysExpiry));

        try {
            zms.putRoleReview(mockDomRsrcCtx, "role-review-dom", "role1", auditRef, inputRole);
            fail();
        } catch (ResourceException re) {
            assertEquals(re.getCode(), 400);
        }

        inputRole.setName("role2");

        RoleMeta rm = createRoleMetaObject(true);
        rm.setMemberExpiryDays(45);
        zms.putRoleMeta(mockDomRsrcCtx, "role-review-dom", "role1", auditRef, rm);

        try {
            zms.putRoleReview(mockDomRsrcCtx, "role-review-dom", "role1", auditRef, inputRole);
            fail();
        } catch (ResourceException re) {
            assertEquals(re.getCode(), 400);
        }

        inputRole.setName("role1");
        try {
            zms.putRoleReview(mockDomRsrcCtx, "role-review-dom1", "role1", auditRef, inputRole);
            fail();
        } catch (ResourceException re) {
            assertEquals(re.getCode(), 404);
        }

        zms.deleteTopLevelDomain(mockDomRsrcCtx, "role-review-dom", auditRef);
    }

    @Test
    public void testPutRoleReviewAuditEnabled() {

        TopLevelDomain dom1 = createTopLevelDomainObject("role-review-dom",
                "Role review Test Domain1", "testOrg", "user.user1");
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        Role role1 = createRoleObject("role-review-dom", "role1", null,
                "user.john", "user.jane");
        zms.putRole(mockDomRsrcCtx, "role-review-dom", "role1", auditRef, role1);

        Timestamp tenDaysExpiry = Timestamp.fromMillis(System.currentTimeMillis() + TimeUnit.MILLISECONDS.convert(10, TimeUnit.DAYS));
        Timestamp sixtyDaysExpiry = Timestamp.fromMillis(System.currentTimeMillis() + TimeUnit.MILLISECONDS.convert(60, TimeUnit.DAYS));
        Timestamp fortyFiveDaysLowerBoundExpiry = Timestamp.fromMillis(System.currentTimeMillis() + TimeUnit.MILLISECONDS.convert(45, TimeUnit.DAYS));

        Membership mbr = generateMembership("role1", "user.doe", tenDaysExpiry);
        zms.putMembership(mockDomRsrcCtx, "role-review-dom", "role1", "user.doe", auditRef, mbr);

        RoleMeta rm = createRoleMetaObject(true);
        rm.setMemberExpiryDays(45);
        zms.putRoleMeta(mockDomRsrcCtx, "role-review-dom", "role1", auditRef, rm);

        DomainMeta meta = createDomainMetaObject("Domain Meta for Role review test", "NewOrg",
                true, true, "12345", 1001);
        zms.putDomainMeta(mockDomRsrcCtx, "role-review-dom", auditRef, meta);
        zms.putDomainSystemMeta(mockDomRsrcCtx, "role-review-dom", "auditenabled", auditRef, meta);

        RoleSystemMeta rsm = createRoleSystemMetaObject(true);
        zms.putRoleSystemMeta(mockDomRsrcCtx, "role-review-dom", "role1", "auditenabled", auditRef, rsm);

        Role inputRole = new Role().setName("role1");
        List<RoleMember> inputMembers = new ArrayList<>();
        inputRole.setRoleMembers(inputMembers);
        inputMembers.add(new RoleMember().setMemberName("user.john").setActive(false));
        inputMembers.add(new RoleMember().setMemberName("user.doe").setActive(true).setExpiration(sixtyDaysExpiry));
        zms.putRoleReview(mockDomRsrcCtx, "role-review-dom", "role1", auditRef, inputRole);

        Role resRole1 = zms.getRole(mockDomRsrcCtx, "role-review-dom", "role1", false, false, true);

        Timestamp fortyFiveDaysUpperBoundExpiry = Timestamp.fromMillis(System.currentTimeMillis() +
                TimeUnit.MILLISECONDS.convert(45, TimeUnit.DAYS) + TimeUnit.MILLISECONDS.convert(5, TimeUnit.MINUTES));

        int userChecked = 0;
        for (RoleMember roleMember : resRole1.getRoleMembers()) {
            if (roleMember.getMemberName().equals("user.jane")) {
                userChecked += 1;
                assertTrue(roleMember.getExpiration().toDate().after(fortyFiveDaysLowerBoundExpiry.toDate())
                        && roleMember.getExpiration().toDate().before(fortyFiveDaysUpperBoundExpiry.toDate()));
                assertTrue(roleMember.getApproved());
            }

            // 2 records for user.doe - one approved before making the domain auditEnabled with
            //expiry date = now + 10 and another pending as part of putRoleReview with expiry date = now + 45

            if (roleMember.getMemberName().equals("user.doe")) {
                userChecked += 1;
                if (roleMember.getApproved() == Boolean.TRUE) {
                    assertEquals(roleMember.getExpiration(), tenDaysExpiry);
                } else {
                    assertTrue(roleMember.getExpiration().toDate().after(fortyFiveDaysLowerBoundExpiry.toDate())
                            && roleMember.getExpiration().toDate().before(fortyFiveDaysUpperBoundExpiry.toDate()));
                }

            }
        }
        assertEquals(userChecked, 3);
        zms.deleteTopLevelDomain(mockDomRsrcCtx, "role-review-dom", auditRef);
    }

    @Test
    public void testLoadServerPrivateKey() {

        // first we try with ec private key only

        System.setProperty(FilePrivateKeyStore.ATHENZ_PROP_PRIVATE_EC_KEY, "src/test/resources/unit_test_zms_private.pem");
        System.clearProperty(FilePrivateKeyStore.ATHENZ_PROP_PRIVATE_RSA_KEY);
        System.clearProperty(FilePrivateKeyStore.ATHENZ_PROP_PRIVATE_KEY);

        zms.loadPrivateKeyStore();
        assertNotNull(zms.privateECKey);
        assertEquals(zms.privateKey, zms.privateECKey);
        assertNull(zms.privateRSAKey);

        // now let's try the rsa key

        System.setProperty(FilePrivateKeyStore.ATHENZ_PROP_PRIVATE_RSA_KEY, "src/test/resources/unit_test_zms_private.pem");
        System.clearProperty(FilePrivateKeyStore.ATHENZ_PROP_PRIVATE_EC_KEY);
        System.clearProperty(FilePrivateKeyStore.ATHENZ_PROP_PRIVATE_KEY);

        zms.loadPrivateKeyStore();
        assertNotNull(zms.privateRSAKey);
        assertEquals(zms.privateKey, zms.privateRSAKey);
        assertNull(zms.privateECKey);

        // now back to our regular key setup

        System.setProperty(FilePrivateKeyStore.ATHENZ_PROP_PRIVATE_KEY, "src/test/resources/unit_test_zms_private.pem");
        System.clearProperty(FilePrivateKeyStore.ATHENZ_PROP_PRIVATE_EC_KEY);
        System.clearProperty(FilePrivateKeyStore.ATHENZ_PROP_PRIVATE_RSA_KEY);

        zms.loadPrivateKeyStore();
        assertNotNull(zms.privateKey);
        assertNull(zms.privateECKey);
        assertNull(zms.privateRSAKey);
    }

    @Test
    public void testLoadInvalidClasses() {

        ZMSImpl zmsImpl = zmsInit();

        System.setProperty(ZMSConsts.ZMS_PROP_METRIC_FACTORY_CLASS, "invalid.class");
        try {
            zmsImpl.loadMetricObject();
            fail();
        } catch (Exception ex) {
            assertTrue(ex.getMessage().contains("Invalid metric class"));
        }
        System.clearProperty(ZMSConsts.ZMS_PROP_METRIC_FACTORY_CLASS);

        System.setProperty(ZMSConsts.ZMS_PROP_OBJECT_STORE_FACTORY_CLASS, "invalid.class");
        try {
            zmsImpl.loadObjectStore();
            fail();
        } catch (Exception ex) {
            assertTrue(ex.getMessage().contains("Invalid object store"));
        }
        System.clearProperty(ZMSConsts.ZMS_PROP_OBJECT_STORE_FACTORY_CLASS);

        System.setProperty(ZMSConsts.ZMS_PROP_PRIVATE_KEY_STORE_FACTORY_CLASS, "invalid.class");
        try {
            zmsImpl.loadPrivateKeyStore();
            fail();
        } catch (Exception ex) {
            assertTrue(ex.getMessage().contains("Invalid private key store"));
        }
        System.clearProperty(ZMSConsts.ZMS_PROP_PRIVATE_KEY_STORE_FACTORY_CLASS);

        System.setProperty(ZMSConsts.ZMS_PROP_AUDIT_LOGGER_FACTORY_CLASS, "invalid.class");
        try {
            zmsImpl.loadAuditLogger();
            fail();
        } catch (Exception ex) {
            assertTrue(ex.getMessage().contains("Invalid audit logger class"));
        }
        System.clearProperty(ZMSConsts.ZMS_PROP_AUDIT_LOGGER_FACTORY_CLASS);

        assertNull(zmsImpl.getAuthority("invalid.class"));

        System.setProperty(ZMSConsts.ZMS_PROP_AUTHORITY_CLASSES, "invalid.class");
        try {
            zmsImpl.loadAuthorities();
            fail();
        } catch (Exception ex) {
            assertTrue(ex.getMessage().contains("Invalid authority"));
        }
        System.clearProperty(ZMSConsts.ZMS_PROP_AUTHORITY_CLASSES);

        System.setProperty(ZMSConsts.ZMS_PROP_STATUS_CHECKER_FACTORY_CLASS, "invalid.class");
        try {
            zmsImpl.loadStatusChecker();
            fail();
        } catch (Exception ex) {
            assertTrue(ex.getMessage().contains("Invalid status checker"));
        }
        System.clearProperty(ZMSConsts.ZMS_PROP_STATUS_CHECKER_FACTORY_CLASS);
        zmsImpl.objectStore.clearConnections();
    }

    @Test
    public void testInvalidConfigValues() {

        ZMSImpl zmsImpl = zmsInit();

        System.setProperty(ZMSConsts.ZMS_PROP_VIRTUAL_DOMAIN_LIMIT, "-10");
        System.setProperty(ZMSConsts.ZMS_PROP_SIGNED_POLICY_TIMEOUT, "-10");

        zmsImpl.loadConfigurationSettings();

        assertEquals(zmsImpl.virtualDomainLimit, 5);
        assertEquals(zmsImpl.signedPolicyTimeout, 604800000);

        System.clearProperty(ZMSConsts.ZMS_PROP_VIRTUAL_DOMAIN_LIMIT);
        System.clearProperty(ZMSConsts.ZMS_PROP_SIGNED_POLICY_TIMEOUT);

        zmsImpl.objectStore.clearConnections();
    }

    @Test
    public void testLoadAuthorities() {

        ZMSImpl zmsImpl = zmsInit();

        assertNull(zmsImpl.userAuthority);
        assertNull(zmsImpl.principalAuthority);

        // set the authority class properties

        System.setProperty(ZMSConsts.ZMS_PROP_AUTHORITY_CLASSES, ZMSConsts.ZMS_PRINCIPAL_AUTHORITY_CLASS);
        System.setProperty(ZMSConsts.ZMS_PROP_PRINCIPAL_AUTHORITY_CLASS, ZMSConsts.ZMS_PRINCIPAL_AUTHORITY_CLASS);
        System.setProperty(ZMSConsts.ZMS_PROP_USER_AUTHORITY_CLASS, ZMSConsts.ZMS_PRINCIPAL_AUTHORITY_CLASS);

        zmsImpl.loadAuthorities();

        assertNotNull(zmsImpl.userAuthority);
        assertNotNull(zmsImpl.principalAuthority);

        System.clearProperty(ZMSConsts.ZMS_PROP_AUTHORITY_CLASSES);
        System.clearProperty(ZMSConsts.ZMS_PROP_PRINCIPAL_AUTHORITY_CLASS);
        System.clearProperty(ZMSConsts.ZMS_PROP_USER_AUTHORITY_CLASS);
        zmsImpl.objectStore.clearConnections();
    }

    @Test
    public void testAutoApplyTemplate() {

        ZMSImpl zmsImpl = zmsInit();

        System.setProperty(ZMSConsts.ZMS_AUTO_UPDATE_TEMPLATE_FEATURE_FLAG, "true");
        try {
            zmsImpl.autoApplyTemplates();
        } catch (Exception e) {
            fail();
        }
        System.clearProperty(ZMSConsts.ZMS_AUTO_UPDATE_TEMPLATE_FEATURE_FLAG);
        zmsImpl.objectStore.clearConnections();
    }

    @Test
    public void testLoadPublicKeysInvalidService() {

        ZMSImpl zmsImpl = zmsInit();

        // delete all public keys from zms service

        ServiceIdentity serviceRes = zmsImpl.getServiceIdentity(mockDomRsrcCtx, "sys.auth", "zms");
        List<PublicKeyEntry> keyList = serviceRes.getPublicKeys();
        for (PublicKeyEntry entry : keyList) {
            zms.deletePublicKeyEntry(mockDomRsrcCtx, "sys.auth", "zms", entry.getId(), auditRef);
        }

        // delete all public keys and load again

        zmsImpl.serverPublicKeyMap.clear();
        loadServerPublicKeys(zmsImpl);

        assertFalse(zmsImpl.serverPublicKeyMap.isEmpty());

        // now verify without the zms service

        zmsImpl.deleteServiceIdentity(mockDomRsrcCtx, "sys.auth", "zms", auditRef);

        // delete all public keys and load again

        zmsImpl.serverPublicKeyMap.clear();
        loadServerPublicKeys(zmsImpl);

        assertFalse(zmsImpl.serverPublicKeyMap.isEmpty());
        zmsImpl.objectStore.clearConnections();
    }

    @Test
    public void testAddDefaultAdminMembers() {

        final String domainName = "add-default-domain-admins";

        TopLevelDomain dom1 = createTopLevelDomainObject(domainName,
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        Role adminRole = zms.getRole(mockDomRsrcCtx, domainName, "admin", false, false, false);
        assertEquals(adminRole.getRoleMembers().size(), 1);

        List<String> admins = new ArrayList<>();
        admins.add(adminUser);
        admins.add("user.default");

        DefaultAdmins addDefaultAdmins = new DefaultAdmins().setAdmins(admins);
        zms.addDefaultAdminMembers(mockDomRsrcCtx, domainName, adminRole, addDefaultAdmins, auditRef, "unittest");

        adminRole = zms.getRole(mockDomRsrcCtx, domainName, "admin", false, false, false);
        assertEquals(adminRole.getRoleMembers().size(), 2);
        boolean newAdminFound = false;
        for (RoleMember roleMember : adminRole.getRoleMembers()) {
            if (roleMember.getMemberName().equals("user.default")) {
                newAdminFound = true;
                break;
            }
        }
        assertTrue(newAdminFound);

        // now let's delete the default admin user

        admins = new ArrayList<>();
        admins.add("user.default");
        admins.add("user.unknown");
        DefaultAdmins deleteDefaultAdmins = new DefaultAdmins().setAdmins(admins);

        zms.removeAdminMembers(mockDomRsrcCtx, domainName, Collections.singletonList(adminRole),
                adminRole.getName(), deleteDefaultAdmins, auditRef, "unittest");

        // verify we're back to a single admin role member

        adminRole = zms.getRole(mockDomRsrcCtx, domainName, "admin", false, false, false);
        assertEquals(adminRole.getRoleMembers().size(), 1);
        assertEquals(adminRole.getRoleMembers().get(0).getMemberName(), adminUser);

        zms.deleteTopLevelDomain(mockDomRsrcCtx, domainName, auditRef);
    }

    @Test
    public void testGetJWSDomain() throws JsonProcessingException {

        final String domainName = "jws-domain";

        // create multiple top level domains
        TopLevelDomain dom1 = createTopLevelDomainObject(domainName,
                "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        JWSDomain jwsDomain = zms.getJWSDomain(mockDomRsrcCtx, domainName);
        assertNotNull(jwsDomain);

        final Base64.Decoder decoder = Base64.getUrlDecoder();

        final String payload = jwsDomain.getPayload();
        final String protectedHeader = jwsDomain.getProtectedHeader();

        assertEquals(new String(decoder.decode(protectedHeader.getBytes(StandardCharsets.UTF_8))), "{\"alg\":\"RS256\"}");

        final String data = protectedHeader + "." + payload;
        final byte[] sig = decoder.decode(jwsDomain.getSignature().getBytes(StandardCharsets.UTF_8));

        // verify the signature

        assertTrue(Crypto.verify(data.getBytes(StandardCharsets.UTF_8),
                Crypto.extractPublicKey(zms.privateKey.getKey()), sig, Crypto.SHA256));

        final String jsonDomain = new String(decoder.decode(payload));

        DomainData domainData = zms.jsonMapper.readValue(jsonDomain, DomainData.class);
        assertNotNull(domainData);
        assertEquals(domainData.getName(), "jws-domain");

        Map<String, String> header = jwsDomain.getHeader();
        assertEquals(header.get("keyid"), "0");

        zms.deleteTopLevelDomain(mockDomRsrcCtx, domainName, auditRef);
    }

    @Test
    public void testGetJWSDomainError() {

        // not found

        try {
            zms.getJWSDomain(mockDomRsrcCtx, "unknown");
            fail();
        } catch (ResourceException ex) {
            assertEquals(ResourceException.NOT_FOUND, ex.getCode());
        }

        // null data causing exception which is caught
        // and we return null back as result

        ServerPrivateKey pkey = zms.privateKey;
        zms.privateKey = null;
        assertNull(zms.signJwsDomain(null));
        zms.privateKey = pkey;
    }

    @Test
    public void testValidateIntegerValue() {

        // valid values

        zms.validateIntegerValue(Integer.valueOf(10), "positive");
        zms.validateIntegerValue(Integer.valueOf(0), "zero");
        zms.validateIntegerValue(null, "null");

        // invalid value

        try {
            zms.validateIntegerValue(Integer.valueOf(-1), "negative");
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), ResourceException.BAD_REQUEST);
        }
    }

    @Test
    public void testIsAllowedDeletePendingMembership() {

        final String domainName = "test-domain";
        TopLevelDomain dom1 = createTopLevelDomainObject(domainName,
                "Role Test Domain1", "testOrg", "user.user1");
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        Role role1 = createRoleObject(domainName, "testrole1", null, "user.user1", "user.jane");
        zms.putRole(mockDomRsrcCtx, domainName, "testrole1", auditRef, role1);

        Policy policy1 = createPolicyObject(domainName, "Policy1", "testrole1",
                "UPDATE", domainName + ":role.*", AssertionEffect.ALLOW);
        zms.putPolicy(mockDomRsrcCtx, domainName, "Policy1", auditRef, policy1);

        assertTrue(zms.isAllowedDeletePendingMembership(mockDomRsrcCtx.principal(), domainName,
                "testrole1", "user.pending"));

        Authority principalAuthority = new com.yahoo.athenz.common.server.debug.DebugPrincipalAuthority();
        String unsignedCreds = "v=U1;d=user;n=jane";
        Principal rsrcPrince = SimplePrincipal.create("user", "jane", unsignedCreds + ";s=signature",0, principalAuthority);
        assertNotNull(rsrcPrince);
        ((SimplePrincipal) rsrcPrince).setUnsignedCreds(unsignedCreds);

        assertTrue(zms.isAllowedDeletePendingMembership(rsrcPrince, domainName,
                "testrole1", "user.pending"));

        unsignedCreds = "v=U1;d=user;n=john";
        rsrcPrince = SimplePrincipal.create("user", "john", unsignedCreds + ";s=signature",0, principalAuthority);
        assertNotNull(rsrcPrince);
        ((SimplePrincipal) rsrcPrince).setUnsignedCreds(unsignedCreds);

        // this time false since john is not authorized

        assertFalse(zms.isAllowedDeletePendingMembership(rsrcPrince, domainName,
                "testrole1", "user.pending"));

        zms.deleteTopLevelDomain(mockDomRsrcCtx, domainName, auditRef);
    }

    @Test
    public void testDeletePendingMembershipAdminRequest() {

        final String domainName = "delete-pending";
        TopLevelDomain dom1 = createTopLevelDomainObject(domainName, "delete pending membership",
                "testOrg", "user.user1");
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        setupPrincipalAuditedRoleApprovalByOrg(zms, "user.fury", "testorg");

        DomainMeta meta = createDomainMetaObject("Domain Meta for approval test", "testorg",
                true, true, "12345", 1001);
        zms.putDomainMeta(mockDomRsrcCtx, domainName, auditRef, meta);
        zms.putDomainSystemMeta(mockDomRsrcCtx, domainName, "auditenabled", auditRef, meta);
        setupPrincipalSystemMetaDelete(zms, mockDomRsrcCtx.principal().getFullName(), domainName, "org");
        zms.putDomainSystemMeta(mockDomRsrcCtx, domainName, "org", auditRef, meta);

        Role auditedRole = createRoleObject(domainName, "testrole1", null, "user.john", "user.jane");
        zms.putRole(mockDomRsrcCtx, domainName, "testrole1", auditRef, auditedRole);
        RoleSystemMeta rsm = createRoleSystemMetaObject(true);
        zms.putRoleSystemMeta(mockDomRsrcCtx, domainName, "testrole1", "auditenabled", auditRef, rsm);

        Membership mbr = new Membership();
        mbr.setMemberName("user.bob");
        mbr.setActive(false);
        mbr.setApproved(false);
        zms.putMembership(mockDomRsrcCtx, domainName, "testrole1", "user.bob", auditRef, mbr);

        // first request using admin principal

        DomainRoleMembership domainRoleMembership = zms.getPendingDomainRoleMembersList(mockDomRsrcCtx, "user.fury");

        assertNotNull(domainRoleMembership);
        assertNotNull(domainRoleMembership.getDomainRoleMembersList());
        assertEquals(domainRoleMembership.getDomainRoleMembersList().size(), 1);
        for (DomainRoleMembers drm : domainRoleMembership.getDomainRoleMembersList()) {
            assertEquals(drm.getDomainName(), domainName);
            assertNotNull(drm.getMembers());
            for (DomainRoleMember mem : drm.getMembers()) {
                assertNotNull(mem);
                assertEquals(mem.getMemberName(), "user.bob");
                for (MemberRole mr : mem.getMemberRoles()) {
                    assertNotNull(mr);
                    assertEquals(mr.getRoleName(), "testrole1");
                }
            }
        }

        Authority principalAuthority = new com.yahoo.athenz.common.server.debug.DebugPrincipalAuthority();
        String unsignedCreds = "v=U1;d=user;n=jane";
        Principal rsrcPrince = SimplePrincipal.create("user", "jane", unsignedCreds + ";s=signature",0, principalAuthority);
        assertNotNull(rsrcPrince);
        ((SimplePrincipal) rsrcPrince).setUnsignedCreds(unsignedCreds);
        ResourceContext ctx = createResourceContext(rsrcPrince);

        // first try to delete the pending request without proper authorization

        try {
            zms.deletePendingMembership(ctx, domainName, "testrole1", "user.bob", auditRef);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 403);
        }

        // repeat the request using context principal

        zms.deletePendingMembership(mockDomRsrcCtx, domainName, "testrole1", "user.bob", auditRef);

        // check the list to see there are no pending requests

        domainRoleMembership = zms.getPendingDomainRoleMembersList(mockDomRsrcCtx, "user.fury");
        assertNotNull(domainRoleMembership);
        assertTrue(domainRoleMembership.getDomainRoleMembersList().isEmpty());

        // delete some unknown member in the same role as admin

        try {
            zms.deletePendingMembership(mockDomRsrcCtx, domainName, "testrole1", "user.bob2", auditRef);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 404);
        }

        // delete some member in an unknown domain

        try {
            zms.deletePendingMembership(mockDomRsrcCtx, "unkwown-domain", "testrole1", "user.bob2", auditRef);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 404);
        }

        cleanupPrincipalSystemMetaDelete(zms);
        clenaupPrincipalAuditedRoleApprovalByOrg(zms, "testOrg");

        zms.deleteTopLevelDomain(mockDomRsrcCtx, domainName, auditRef);
    }

    @Test
    public void testDeletePendingMembershipSelfServeRequest() {

        final String domainName = "delete-pending-self-serve";
        TopLevelDomain dom1 = createTopLevelDomainObject(domainName, "delete pending membership",
                "testOrg", "user.user1");
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        setupPrincipalAuditedRoleApprovalByOrg(zms, "user.fury", "testorg");

        DomainMeta meta = createDomainMetaObject("Domain Meta for approval test", "testorg",
                true, true, "12345", 1001);
        zms.putDomainMeta(mockDomRsrcCtx, domainName, auditRef, meta);
        zms.putDomainSystemMeta(mockDomRsrcCtx, domainName, "auditenabled", auditRef, meta);
        setupPrincipalSystemMetaDelete(zms, mockDomRsrcCtx.principal().getFullName(), domainName, "org");
        zms.putDomainSystemMeta(mockDomRsrcCtx, domainName, "org", auditRef, meta);

        Role auditedRole = createRoleObject(domainName, "testrole1", null, "user.john", "user.jane");
        zms.putRole(mockDomRsrcCtx, domainName, "testrole1", auditRef, auditedRole);
        RoleSystemMeta rsm = createRoleSystemMetaObject(true);
        zms.putRoleSystemMeta(mockDomRsrcCtx, domainName, "testrole1", "auditenabled", auditRef, rsm);
        RoleMeta rm = new RoleMeta().setSelfServe(true);
        zms.putRoleMeta(mockDomRsrcCtx, domainName, "testrole1", auditRef, rm);

        // user.joe is going to add user.bob in the self serve role

        Authority principalAuthority = new com.yahoo.athenz.common.server.debug.DebugPrincipalAuthority();
        String unsignedCreds = "v=U1;d=user;n=joe";
        Principal rsrcPrince = SimplePrincipal.create("user", "joe", unsignedCreds + ";s=signature",0, principalAuthority);
        assertNotNull(rsrcPrince);
        ((SimplePrincipal) rsrcPrince).setUnsignedCreds(unsignedCreds);
        ResourceContext ctxJoe = createResourceContext(rsrcPrince);

        Membership mbr = new Membership();
        mbr.setMemberName("user.bob");
        mbr.setActive(false);
        mbr.setApproved(false);
        zms.putMembership(ctxJoe, domainName, "testrole1", "user.bob", auditRef, mbr);

        // first request using admin principal

        DomainRoleMembership domainRoleMembership = zms.getPendingDomainRoleMembersList(mockDomRsrcCtx, "user.fury");

        assertNotNull(domainRoleMembership);
        assertNotNull(domainRoleMembership.getDomainRoleMembersList());
        assertEquals(domainRoleMembership.getDomainRoleMembersList().size(), 1);
        for (DomainRoleMembers drm : domainRoleMembership.getDomainRoleMembersList()) {
            assertEquals(drm.getDomainName(), domainName);
            assertNotNull(drm.getMembers());
            for (DomainRoleMember mem : drm.getMembers()) {
                assertNotNull(mem);
                assertEquals(mem.getMemberName(), "user.bob");
                for (MemberRole mr : mem.getMemberRoles()) {
                    assertNotNull(mr);
                    assertEquals(mr.getRoleName(), "testrole1");
                }
            }
        }

        // first try to delete the pending request without proper authorization

        unsignedCreds = "v=U1;d=user;n=jane";
        rsrcPrince = SimplePrincipal.create("user", "jane", unsignedCreds + ";s=signature",0, principalAuthority);
        assertNotNull(rsrcPrince);
        ((SimplePrincipal) rsrcPrince).setUnsignedCreds(unsignedCreds);
        ResourceContext ctxJane = createResourceContext(rsrcPrince);

        try {
            zms.deletePendingMembership(ctxJane, domainName, "testrole1", "user.bob", auditRef);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 403);
        }

        // repeat the request using joe principal

        zms.deletePendingMembership(ctxJoe, domainName, "testrole1", "user.bob", auditRef);

        // check the list to see there are no pending requests

        domainRoleMembership = zms.getPendingDomainRoleMembersList(mockDomRsrcCtx, "user.fury");
        assertNotNull(domainRoleMembership);
        assertTrue(domainRoleMembership.getDomainRoleMembersList().isEmpty());

        cleanupPrincipalSystemMetaDelete(zms);
        clenaupPrincipalAuditedRoleApprovalByOrg(zms, "testOrg");

        zms.deleteTopLevelDomain(mockDomRsrcCtx, domainName, auditRef);
    }

    @Test
    public void testGetDomainTemplateDetailsList() {

        final String domainName = "test-domain";
        List<String> adminUsers = new ArrayList<>();
        adminUsers.add("user.test");
        List<String> solutionTemplate = new ArrayList<>();
        solutionTemplate.add("vipng");

        zms.dbService.makeDomain(mockDomRsrcCtx, ZMSTestUtils.makeDomainObject(domainName, "Test Domain", "org",
                true, null, 0, null, 0), adminUsers, solutionTemplate, auditRef);

        assertNotNull(zms.getDomainTemplateDetailsList(mockDomRsrcCtx, domainName));
        zms.dbService.executeDeleteDomain(mockDomRsrcCtx, domainName, auditRef, "unit-test");
    }

    @Test
    public void testEnforcedUserAuthorityFilter() {

        Authority savedAuthority = zms.userAuthority;

        // null authority, filter or empty filter

        zms.userAuthority = null;
        assertNull(zms.enforcedUserAuthorityFilter("validFilter", null));
        assertNull(zms.enforcedUserAuthorityFilter(null, "validFilter"));
        assertNull(zms.enforcedUserAuthorityFilter("validFilter", "validFilter"));
        assertNull(zms.enforcedUserAuthorityFilter(null, null));

        zms.userAuthority = Mockito.mock(Authority.class);

        assertNull(zms.enforcedUserAuthorityFilter(null, null));
        assertNull(zms.enforcedUserAuthorityFilter("", null));
        assertNull(zms.enforcedUserAuthorityFilter(null, ""));

        // valid filter

        assertEquals("validFilter", zms.enforcedUserAuthorityFilter("validFilter", null));
        assertEquals("validFilter", zms.enforcedUserAuthorityFilter(null, "validFilter"));
        assertEquals("validFilter", zms.enforcedUserAuthorityFilter("validFilter", ""));
        assertEquals("validFilter", zms.enforcedUserAuthorityFilter("", "validFilter"));
        assertEquals("validFilter1,validFilter2", zms.enforcedUserAuthorityFilter("validFilter1", "validFilter2"));
        assertEquals("validFilter,validFilter", zms.enforcedUserAuthorityFilter("validFilter", "validFilter"));

        zms.userAuthority = savedAuthority;
    }

    @Test
    public void testGetUserAuthorityExpiryAttr() {

        Role role = new Role().setUserAuthorityExpiration("elevated-clearance");

        Authority savedAuthority = zms.userAuthority;
        zms.userAuthority = null;

        // with authority null we always get null

        assertNull(zms.getUserAuthorityExpiryAttr(role.getUserAuthorityExpiration()));

        zms.userAuthority = Mockito.mock(Authority.class);

        assertEquals("elevated-clearance", zms.getUserAuthorityExpiryAttr(role.getUserAuthorityExpiration()));

        role.setUserAuthorityExpiration("");
        assertNull(zms.getUserAuthorityExpiryAttr(role.getUserAuthorityExpiration()));

        role.setUserAuthorityExpiration(null);
        assertNull(zms.getUserAuthorityExpiryAttr(role.getUserAuthorityExpiration()));

        zms.userAuthority = savedAuthority;
    }

    @Test
    public void testGetUserAuthorityExpiry() {

        Role role = new Role().setUserAuthorityExpiration("elevated-clearance");

        Authority savedAuthority = zms.userAuthority;
        zms.userAuthority = null;

        // with authority null we always get null

        assertNull(zms.getUserAuthorityExpiry("user.john", role.getUserAuthorityExpiration(), "unit-test"));

        Authority authority = Mockito.mock(Authority.class);
        Mockito.when(authority.getDateAttribute("user.john", "elevated-clearance"))
                .thenReturn(new Date());
        Mockito.when(authority.getDateAttribute("user.joe", "elevated-clearance"))
                .thenReturn(null);
        zms.userAuthority = authority;

        assertNotNull(zms.getUserAuthorityExpiry("user.john", role.getUserAuthorityExpiration(), "unit-test"));

        try {
            zms.getUserAuthorityExpiry("user.joe", role.getUserAuthorityExpiration(), "unit-test");
            fail();
        } catch (ResourceException ex) {
            assertTrue(ex.getMessage().contains("User does not have required user authority expiry configured"));
        }

        zms.userAuthority = savedAuthority;
    }

    @Test
    public void testUpdateRoleMemberUserAuthorityExpiry() {

        Role role = new Role().setUserAuthorityExpiration("elevated-clearance");

        List<RoleMember> members = new ArrayList<>();
        members.add(new RoleMember().setMemberName("user.john"));
        members.add(new RoleMember().setMemberName("user.joe"));
        role.setRoleMembers(members);

        Authority savedAuthority = zms.userAuthority;
        zms.userAuthority = null;

        // with authority null we always get no changes

        zms.updateRoleMemberUserAuthorityExpiry(role, "unit-test");
        assertNull(role.getRoleMembers().get(0).getExpiration());
        assertNull(role.getRoleMembers().get(1).getExpiration());

        Authority authority = Mockito.mock(Authority.class);
        Mockito.when(authority.getDateAttribute("user.john", "elevated-clearance"))
                .thenReturn(new Date());
        Mockito.when(authority.getDateAttribute("user.jane", "elevated-clearance"))
                .thenReturn(new Date());
        Mockito.when(authority.getDateAttribute("user.joe", "elevated-clearance"))
                .thenReturn(null);
        zms.userAuthority = authority;

        // with one valid and one invalid we should get an exception

        try {
            zms.updateRoleMemberUserAuthorityExpiry(role, "unit-test");
            fail();
        } catch (ResourceException ex) {
            assertTrue(ex.getMessage().contains("Invalid member: user.joe"));
        }

        // let's have one valid user and one service

        members = new ArrayList<>();
        members.add(new RoleMember().setMemberName("user.john"));
        members.add(new RoleMember().setMemberName("sports.api"));
        role.setRoleMembers(members);

        // the user will have an expiration while service is skipped

        zms.updateRoleMemberUserAuthorityExpiry(role, "unit-test");
        assertNotNull(role.getRoleMembers().get(0).getExpiration());
        assertNull(role.getRoleMembers().get(1).getExpiration());

        // now let's have only user members

        members = new ArrayList<>();
        members.add(new RoleMember().setMemberName("user.john"));
        members.add(new RoleMember().setMemberName("user.jane"));
        role.setRoleMembers(members);

        zms.updateRoleMemberUserAuthorityExpiry(role, "unit-test");
        assertNotNull(role.getRoleMembers().get(0).getExpiration());
        assertNotNull(role.getRoleMembers().get(1).getExpiration());

        zms.userAuthority = savedAuthority;
    }

    @Test
    public void testValidateRoleUserAuthorityAttributes() {

        Authority savedAuthority = zms.userAuthority;
        zms.userAuthority = null;

        // with authority null we always get exceptions if we have
        // not empty values specified

        zms.validateUserAuthorityAttributes(null, null, "unit-test");
        zms.validateUserAuthorityAttributes(null, "", "unit-test");
        zms.validateUserAuthorityAttributes("", null, "unit-test");
        zms.validateUserAuthorityAttributes("", "", "unit-test");

        try {
            zms.validateUserAuthorityAttributes("attr1", null, "unit-test");
            fail();
        } catch (ResourceException ex) {
            assertTrue(ex.getMessage().contains("User Authority filter specified without a valid user authority"));
        }

        try {
            zms.validateUserAuthorityAttributes(null, "attr1", "unit-test");
            fail();
        } catch (ResourceException ex) {
            assertTrue(ex.getMessage().contains("User Authority expiry specified without a valid user authority"));
        }

        Authority authority = Mockito.mock(Authority.class);
        Set<String> booleanAttrSet = new HashSet<>();
        booleanAttrSet.add("elevated-clearance");
        booleanAttrSet.add("full-time-employee");
        Mockito.when(authority.booleanAttributesSupported()).thenReturn(booleanAttrSet);
        Set<String> dateAttrSet = new HashSet<>();
        dateAttrSet.add("term-date");
        Mockito.when(authority.dateAttributesSupported()).thenReturn(dateAttrSet);
        zms.userAuthority = authority;

        // valid values

        zms.validateUserAuthorityAttributes("elevated-clearance", null, "unit-test");
        zms.validateUserAuthorityAttributes("elevated-clearance", "", "unit-test");
        zms.validateUserAuthorityAttributes("elevated-clearance,full-time-employee", null, "unit-test");
        zms.validateUserAuthorityAttributes("full-time-employee,elevated-clearance", "term-date", "unit-test");
        zms.validateUserAuthorityAttributes("", "term-date", "unit-test");
        zms.validateUserAuthorityAttributes(null, "term-date", "unit-test");

        zms.validateUserAuthorityAttributes(null, null, "unit-test");
        zms.validateUserAuthorityAttributes(null, "", "unit-test");
        zms.validateUserAuthorityAttributes("", null, "unit-test");
        zms.validateUserAuthorityAttributes("", "", "unit-test");

        // invalid values

        try {
            zms.validateUserAuthorityAttributes("elevated-clearance,contractor", null, "unit-test");
            fail();
        } catch (ResourceException ex) {
            assertTrue(ex.getMessage().contains("contractor is not a valid user authority attribute"));
        }

        try {
            zms.validateUserAuthorityAttributes("elevated-clearance", "hire-date", "unit-test");
            fail();
        } catch (ResourceException ex) {
            assertTrue(ex.getMessage().contains("hire-date is not a valid user authority date attribute"));
        }

        zms.userAuthority = savedAuthority;
    }

    @Test
    public void testSetRoleMemberExpiration() {

        Authority savedAuthority = zms.userAuthority;
        zms.userAuthority = null;

        // with authority null we always get no changes

        Authority authority = Mockito.mock(Authority.class);
        Date testDate = new Date();
        Mockito.when(authority.getDateAttribute("user.john", "elevated-clearance"))
                .thenReturn(testDate);
        Mockito.when(authority.getDateAttribute("user.jane", "elevated-clearance"))
                .thenReturn(testDate);
        Mockito.when(authority.getDateAttribute("user.joe", "elevated-clearance"))
                .thenReturn(null);
        Set<String> dateAttrSet = new HashSet<>();
        dateAttrSet.add("elevated-clearance");
        Mockito.when(authority.dateAttributesSupported()).thenReturn(dateAttrSet);

        zms.userAuthority = authority;

        // add a role with an elevated clearance option

        final String domainName = "userexpirydomain";
        TopLevelDomain dom1 = createTopLevelDomainObject(domainName,
                "Test Domain1", "testOrg", "user.user1");
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        final String roleName = "audit-role";
        Role role = createRoleObject(domainName, roleName, null, null);
        role.setUserAuthorityExpiration("elevated-clearance");
        zms.putRole(mockDomRsrcCtx, domainName, roleName, auditRef, role);

        // add a valid member who should get the expiry date

        Membership mbr = new Membership().setMemberName("user.john");
        zms.putMembership(mockDomRsrcCtx, domainName, roleName, "user.john", auditRef, mbr);

        Membership mbrResult = zms.getMembership(mockDomRsrcCtx, domainName, roleName, "user.john", null);
        assertNotNull(mbrResult);
        assertEquals(mbrResult.getMemberName(), "user.john");
        assertNotNull(mbrResult.getExpiration());
        assertEquals(mbrResult.getExpiration().millis(), testDate.getTime());

        // user with no expiry

        mbr = new Membership().setMemberName("user.joe");
        try {
            zms.putMembership(mockDomRsrcCtx, domainName, roleName, "user.joe", auditRef, mbr);
            fail();
        } catch (ResourceException ex) {
            assertTrue(ex.getMessage().contains("User does not have required user authority expiry configured"));
        }

        // service user should be added ok since service user is not processed
        // by user authority

        mbr = new Membership().setMemberName("userexpirydomain.api");
        zms.putMembership(mockDomRsrcCtx, domainName, roleName, "userexpirydomain.api", auditRef, mbr);
        mbrResult = zms.getMembership(mockDomRsrcCtx, domainName, roleName, "userexpirydomain.api", null);
        assertNotNull(mbrResult);

        zms.userAuthority = savedAuthority;
        zms.deleteTopLevelDomain(mockDomRsrcCtx, domainName, auditRef);
    }

    @Test
    public void testIsMemberEnabled() {

        RoleMember roleMember = new RoleMember();

        roleMember.setSystemDisabled(null);
        assertTrue(zms.isMemberEnabled(roleMember));

        roleMember.setSystemDisabled(0);
        assertTrue(zms.isMemberEnabled(roleMember));

        roleMember.setSystemDisabled(1);
        assertFalse(zms.isMemberEnabled(roleMember));

        roleMember.setSystemDisabled(3);
        assertFalse(zms.isMemberEnabled(roleMember));
    }

    @Test
    public void testIsMemberExpired() {

        RoleMember roleMember = new RoleMember();

        roleMember.setExpiration(null);
        assertFalse(zms.isMemberExpired(roleMember));

        roleMember.setExpiration(Timestamp.fromMillis(System.currentTimeMillis() + 100000));
        assertFalse(zms.isMemberExpired(roleMember));

        roleMember.setExpiration(Timestamp.fromMillis(System.currentTimeMillis() - 1));
        assertTrue(zms.isMemberExpired(roleMember));
    }

    @Test
    public void testCheckRoleMemberValidity() {

        List<RoleMember> roleMembers = new ArrayList<>();

        // valid members

        RoleMember roleMemberJoe = new RoleMember()
                .setMemberName("user.joe");
        RoleMember roleMemberJane = new RoleMember()
                .setMemberName("user.jane")
                .setSystemDisabled(null)
                .setExpiration(null);
        RoleMember roleMemberJohn = new RoleMember()
                .setSystemDisabled(0)
                .setMemberName("user.john")
                .setExpiration(Timestamp.fromMillis(System.currentTimeMillis() + 100000));

        roleMembers.add(roleMemberJoe);
        roleMembers.add(roleMemberJane);
        roleMembers.add(roleMemberJohn);

        // invalid members

        RoleMember roleMemberJoeBad = new RoleMember()
                .setMemberName("user.joe-bad")
                .setSystemDisabled(1);
        RoleMember roleMemberJaneBad = new RoleMember()
                .setMemberName("user.jane-bad")
                .setSystemDisabled(null)
                .setExpiration(Timestamp.fromMillis(System.currentTimeMillis() - 1));
        RoleMember roleMemberJohnBad = new RoleMember()
                .setSystemDisabled(3)
                .setMemberName("user.john-bad")
                .setExpiration(Timestamp.fromMillis(System.currentTimeMillis() - 10000));

        roleMembers.add(roleMemberJoeBad);
        roleMembers.add(roleMemberJaneBad);
        roleMembers.add(roleMemberJohnBad);

        // carry out the checks

        assertTrue(zms.checkRoleMemberValidity(roleMembers, "user.joe"));
        assertTrue(zms.checkRoleMemberValidity(roleMembers, "user.jane"));
        assertTrue(zms.checkRoleMemberValidity(roleMembers, "user.john"));

        assertFalse(zms.checkRoleMemberValidity(roleMembers, "user.joe-bad"));
        assertFalse(zms.checkRoleMemberValidity(roleMembers, "user.jane-bad"));
        assertFalse(zms.checkRoleMemberValidity(roleMembers, "user.john-bad"));
    }

    @DataProvider(name = "delegatedRoles")
    public static Object[][] getDelegatedRoles() {
        String domainName = "test_domain";

        Role role1 = new Role();
        String memberName = "member";
        RoleMember roleMember = new RoleMember().setMemberName(memberName);

        Role role2 = new Role();
        role2.setMembers(Collections.singletonList(memberName));
        role2.setRoleMembers(Collections.singletonList(roleMember));

        Role role3 = new Role();
        role3.setRoleMembers(Collections.singletonList(roleMember));

        Role role4 = new Role();
        role4.setRoleMembers(Collections.singletonList(roleMember));
        role4.setTrust("trust");

        Role role5 = new Role();
        role5.setMembers(Collections.singletonList(memberName));
        role5.setTrust("trust");

        Role role6 = new Role();
        role6.setTrust("trust");

        Role role7 = new Role();
        role7.setTrust("trust-notfound");

        return new Object[][] {
                {domainName, role1, false},
                {domainName, role2, true},
                {domainName, role3, false},
                {domainName, role4, true},
                {domainName, role5, true},
                {"trust", role6, true},
                {"test_domain", role6, false},
                {"test_domain", role7, true}
        };
    }

    @Test(dataProvider = "delegatedRoles")
    public void testValidateRoleStructure(String domainName, Role role, boolean expectedFailure) {

        final String trustDomainName = "trust";
        TopLevelDomain dom1 = createTopLevelDomainObject(trustDomainName,
                "Test Domain1", "testOrg", "user.user1");
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        try {
            zms.validateRoleStructure(role, domainName, "unittest");
            if (expectedFailure) {
                fail();
            }
        } catch (ResourceException e) {
            if (expectedFailure) {
                assertEquals(e.getCode(), 400);
            } else {
                fail("should not have failed with ResourceException");
            }
        }

        zms.deleteTopLevelDomain(mockDomRsrcCtx, trustDomainName, auditRef);
    }

    @Test
    public void testRecordMetricsUnauthenticated() {
        zms.metric = Mockito.mock(Metric.class);
        RsrcCtxWrapper ctx = (RsrcCtxWrapper) zms.newResourceContext(mockServletRequest, mockServletResponse, "someApiMethod");
        String testDomain = "testDomain";
        int httpStatus = 200;
        ctx.setRequestDomain(testDomain);
        zms.recordMetrics(ctx, httpStatus);
        Mockito.verify(zms.metric,
                times(1)).increment (
                eq("zms_api"),
                eq(testDomain),
                eq(null),
                eq("GET"),
                eq(httpStatus),
                eq("someapimethod"));
        Mockito.verify(zms.metric,
                times(1)).stopTiming (
                eq(ctx.getTimerMetric()),
                eq(testDomain),
                eq(null),
                eq("GET"),
                eq(httpStatus),
                eq("someapimethod_timing"));
        Mockito.verify(zms.metric,
                times(1)).startTiming (
                eq("zms_api_latency"),
                eq(null),
                eq(null),
                eq("GET"),
                eq("someapimethod"));
    }

    @Test
    public void testRecordMetricsAuthenticated() {
        zms.metric = Mockito.mock(Metric.class);
        RsrcCtxWrapper ctx = mockDomRsrcCtx;
        String testDomain = "testDomain";
        int httpStatus = 200;
        Mockito.when(ctx.getRequestDomain()).thenReturn(testDomain);
        zms.recordMetrics(ctx, httpStatus);
        Mockito.verify(zms.metric,
                times(1)).increment (
                eq("zms_api"),
                eq(testDomain),
                eq("user"),
                eq("GET"),
                eq(httpStatus),
                eq("someApiMethod"));
        Mockito.verify(zms.metric,
                times(1)).stopTiming (
                eq(ctx.getTimerMetric()),
                eq(testDomain),
                eq("user"),
                eq("GET"), eq(httpStatus), eq("someApiMethod_timing"));
    }

    @Test
    public void testRecordMetricsNoCtx() {
        int httpStatus = 200;
        zms.metric = Mockito.mock(Metric.class);
        zms.recordMetrics(null, httpStatus);
        Mockito.verify(zms.metric,
                times(1)).increment (
                eq("zms_api"),
                eq(null),
                eq(null),
                eq(null),
                eq(httpStatus),
                eq(null));
        Mockito.verify(zms.metric,
                times(1)).stopTiming (
                eq(null),
                eq(null),
                eq(null),
                eq(null),
                eq(httpStatus),
                eq(null));
    }

    @Test
    public void testGetGroupWithAttributes() {

        final String domainName = "put-domain-group1";
        TopLevelDomain dom1 = createTopLevelDomainObject(domainName, "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        Group group1 = createGroupObject(domainName, "group1", "user.joe", "user.jane");
        group1.setSelfServe(true);
        zms.putGroup(mockDomRsrcCtx, domainName, "group1", auditRef, group1);

        Group group1a = zms.getGroup(mockDomRsrcCtx, domainName, "group1", false, false);
        assertNotNull(group1a);

        assertEquals(group1a.getName(), domainName + ":group.group1");
        List<GroupMember> members = group1a.getGroupMembers();
        assertNotNull(members);
        assertEquals(members.size(), 2);

        List<String> checkList = new ArrayList<>();
        checkList.add("user.joe");
        checkList.add("user.jane");
        checkGroupMember(checkList, members);
        assertTrue(group1a.getSelfServe());

        // get unknown group

        try {
            zms.getGroup(mockDomRsrcCtx, domainName, "uknown-group", false, false);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), ResourceException.NOT_FOUND);
        }

        zms.deleteTopLevelDomain(mockDomRsrcCtx, domainName, auditRef);
    }

    @Test
    public void testCreateGroup() {

        final String domainName = "create-group";
        final String groupName = "group1";

        TestAuditLogger alogger = new TestAuditLogger();
        List<String> aLogMsgs = alogger.getLogMsgList();
        ZMSImpl zmsImpl = getZmsImpl(alogger);

        TopLevelDomain dom1 = createTopLevelDomainObject(domainName, "Test Domain1", "testOrg", adminUser);
        Mockito.when(mockDomRsrcCtx.getApiName()).thenReturn("posttopleveldomain").thenReturn("putgroup");
        zmsImpl.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        Group group1 = createGroupObject(domainName, groupName, "user.joe", "user.jane");
        zmsImpl.putGroup(mockDomRsrcCtx, domainName, groupName, auditRef, group1);

        Group group1a = zmsImpl.getGroup(mockDomRsrcCtx, domainName, groupName, true, false);
        assertNotNull(group1a);
        assertNotNull(group1a.getAuditLog());
        assertEquals(group1a.getAuditLog().size(), 2);
        assertEquals(group1a.getName(), domainName + ":group." + groupName);
        assertNull(group1a.getAuditEnabled());
        assertNull(group1a.getReviewEnabled());

        // check audit log msg for putRole
        boolean foundError = false;
        System.err.println("testCreateGroup: Number of lines: " + aLogMsgs.size());
        for (String msg: aLogMsgs) {
            if (!msg.contains("WHAT-api=(putgroup)")) {
                continue;
            }
            assertTrue(msg.contains("CLIENT-IP=(" + MOCKCLIENTADDR + ")"), msg);
            int index = msg.indexOf("WHAT-details=(");
            assertTrue(index != -1, msg);
            int index2 = msg.indexOf("\"name\": \"" + groupName + "\", \"added-members\": [");
            assertTrue(index2 > index, msg);
            foundError = true;
            break;
        }
        assertTrue(foundError);

        // delete member of the role
        //
        List<GroupMember> listrm = group1.getGroupMembers();
        for (GroupMember rmemb: listrm) {
            if (rmemb.getMemberName().equals("user.jane")) {
                listrm.remove(rmemb);
                break;
            }
        }

        aLogMsgs.clear();
        zmsImpl.putGroup(mockDomRsrcCtx, domainName, groupName, auditRef, group1);

        foundError = false;
        System.err.println("testCreateGroup: Now Number of lines: " + aLogMsgs.size());
        for (String msg: aLogMsgs) {
            if (!msg.contains("WHAT-api=(putgroup)")) {
                continue;
            }
            assertTrue(msg.contains("CLIENT-IP=(" + MOCKCLIENTADDR + ")"), msg);
            int index = msg.indexOf("WHAT-details=(");
            assertTrue(index != -1, msg);
            int index2 = msg.indexOf("\"name\": \"" + groupName + "\", \"deleted-members\": [{\"member\": \"user.jane\", \"approved\": true, \"system-disabled\": 0}], \"added-members\": []");
            assertTrue(index2 > index, msg);
            foundError = true;
            break;
        }
        assertTrue(foundError);

        zmsImpl.deleteTopLevelDomain(mockDomRsrcCtx, domainName, auditRef);
    }

    @Test
    public void testCreateGroupDBFailure() {

        final String domainName = "create-group-db-failure";

        TopLevelDomain dom1 = createTopLevelDomainObject(domainName, "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        // put the db in read-only mode

        setDatabaseReadOnlyMode(true);

        Group group1 = createGroupObject(domainName, "group1", "user.joe", "user.jane");
        try {
            zms.putGroup(mockDomRsrcCtx, domainName, "group1", auditRef, group1);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), ResourceException.GONE);
        }

        // remove read-only mode

        setDatabaseReadOnlyMode(false);

        zms.deleteTopLevelDomain(mockDomRsrcCtx, domainName, auditRef);
    }

    @Test
    public void testCreateDuplicateMemberGroup() {

        final String domainName = "dup-member-group";
        final String groupName = "group1";

        TopLevelDomain dom1 = createTopLevelDomainObject(domainName, "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        Group group1 = createGroupObject(domainName, groupName, "user.joe", "user.joe");
        zms.putGroup(mockDomRsrcCtx, domainName, groupName, auditRef, group1);

        Group group = zms.getGroup(mockDomRsrcCtx, domainName, groupName, false, false);
        assertNotNull(group);

        assertEquals(group.getName(), domainName + ":group.group1".toLowerCase());
        List<GroupMember> members = group.getGroupMembers();
        assertNotNull(members);
        assertEquals(members.size(), 1);
        assertEquals("user.joe", members.get(0).getMemberName());

        zms.deleteTopLevelDomain(mockDomRsrcCtx,domainName, auditRef);
    }

    @Test
    public void testPutGroupUpdate() {

        final String domainName = "update-group";
        final String groupName = "group1";

        TopLevelDomain dom1 = createTopLevelDomainObject(domainName, "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        Group group1 = createGroupObject(domainName, groupName, "user.joe", "user.jane");
        zms.putGroup(mockDomRsrcCtx, domainName, groupName, auditRef, group1);

        group1 = createGroupObject(domainName, groupName, "user.john", "user.joe");
        zms.putGroup(mockDomRsrcCtx, domainName, groupName, auditRef, group1);

        Group group = zms.getGroup(mockDomRsrcCtx, domainName, groupName, false, false);
        assertNotNull(group);

        assertEquals(group.getName(), domainName + ":group.group1".toLowerCase());
        List<GroupMember> members = group.getGroupMembers();
        assertNotNull(members);
        assertEquals(members.size(), 2);
        List<String> checkList = new ArrayList<>();
        checkList.add("user.joe");
        checkList.add("user.john");
        checkGroupMember(checkList, members);

        zms.deleteTopLevelDomain(mockDomRsrcCtx,domainName, auditRef);
    }

    @Test
    public void testPutGroupExceptions() {

        final String domainName = "put-group-exc";
        final String groupName = "group1";

        TopLevelDomain dom1 = createTopLevelDomainObject(domainName, "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        // inconsistent group name

        Group group1 = createGroupObject(domainName, groupName, "user.joe", "user.jane");
        try {
            zms.putGroup(mockDomRsrcCtx, domainName, "different-group", auditRef, group1);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), ResourceException.BAD_REQUEST);
        }

        // unknown domain

        try {
            group1 = createGroupObject("unknown-domain", groupName, "user.joe", "user.jane");
            zms.putGroup(mockDomRsrcCtx, "unknown-domain", groupName, auditRef, group1);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), ResourceException.NOT_FOUND);
        }

        // review enabled with members

        group1 = createGroupObject(domainName, groupName, "user.joe", "user.jane");
        group1.setReviewEnabled(true);
        try {
            zms.putGroup(mockDomRsrcCtx, domainName, groupName, auditRef, group1);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), ResourceException.BAD_REQUEST);
        }

        zms.deleteTopLevelDomain(mockDomRsrcCtx,domainName, auditRef);
    }

    @Test
    public void testCreateNormalizedUserMemberGroup() {

        final  String domainName = "norm-user-member-group";
        final String groupName = "group1";
        final String groupName2 = "group2";

        TopLevelDomain dom1 = createTopLevelDomainObject(domainName, "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        ArrayList<GroupMember> groupMembers = new ArrayList<>();
        groupMembers.add(new GroupMember().setMemberName("user.joe"));
        groupMembers.add(new GroupMember().setMemberName("user.joe"));
        groupMembers.add(new GroupMember().setMemberName("user.joe"));
        groupMembers.add(new GroupMember().setMemberName("user.jane"));

        Group group1 = createGroupObject(domainName, groupName, groupMembers);
        zms.putGroup(mockDomRsrcCtx, domainName, groupName, auditRef, group1);

        Group group = zms.getGroup(mockDomRsrcCtx, domainName, groupName, false, false);
        assertNotNull(group);

        assertEquals(group.getName(), domainName + ":group.group1".toLowerCase());
        List<GroupMember> members = group.getGroupMembers();
        assertNotNull(members);
        assertEquals(members.size(), 2);
        List<String> checkList = new ArrayList<>();
        checkList.add("user.joe");
        checkList.add("user.jane");
        checkGroupMember(checkList, members);

        // create a group with no members

        Group group2 = createGroupObject(domainName, groupName2, null);
        zms.putGroup(mockDomRsrcCtx, domainName, groupName2, auditRef, group2);

        group = zms.getGroup(mockDomRsrcCtx, domainName, groupName2, false, false);
        assertNotNull(group);
        assertTrue(group.getGroupMembers().isEmpty());

        zms.deleteTopLevelDomain(mockDomRsrcCtx,domainName, auditRef);
    }

    @Test
    public void testCreateNormalizedServiceMemberGroup() {

        final String domainName = "norm-svc-member-group";
        final String groupName = "group1";

        TopLevelDomain dom1 = createTopLevelDomainObject(domainName, "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        TopLevelDomain dom2 = createTopLevelDomainObject("coretech",
                "Test Domain2", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom2);

        SubDomain subDom2 = createSubDomainObject("storage", "coretech",
                "Test Domain2", "testOrg", adminUser);
        zms.postSubDomain(mockDomRsrcCtx, "coretech", auditRef, subDom2);

        SubDomain subDom3 = createSubDomainObject("user1", "user",
                "Test Domain2", "testOrg", adminUser);
        zms.postSubDomain(mockDomRsrcCtx, "user", auditRef, subDom3);

        SubDomain subDom4 = createSubDomainObject("dom1", "user.user1",
                "Test Domain2", "testOrg", adminUser);
        zms.postSubDomain(mockDomRsrcCtx, "user.user1", auditRef, subDom4);

        ArrayList<GroupMember> groupMembers = new ArrayList<>();
        groupMembers.add(new GroupMember().setMemberName("coretech.storage"));
        groupMembers.add(new GroupMember().setMemberName("coretech.storage"));
        groupMembers.add(new GroupMember().setMemberName("user.user1.dom1.api"));

        Group group1 = createGroupObject(domainName, groupName, groupMembers);
        zms.putGroup(mockDomRsrcCtx, domainName, groupName, auditRef, group1);

        Group group = zms.getGroup(mockDomRsrcCtx, domainName, groupName, false, false);
        assertNotNull(group);

        assertEquals(group.getName(), domainName + ":group.Group1".toLowerCase());
        List<GroupMember> members = group.getGroupMembers();
        assertNotNull(members);
        assertEquals(members.size(), 2);
        List<String> checkList = new ArrayList<>();
        checkList.add("coretech.storage");
        checkList.add("user.user1.dom1.api");
        checkGroupMember(checkList, members);

        zms.deleteSubDomain(mockDomRsrcCtx, "user.user1", "dom1", auditRef);
        zms.deleteSubDomain(mockDomRsrcCtx, "user", "user1", auditRef);
        zms.deleteSubDomain(mockDomRsrcCtx, "coretech", "storage", auditRef);
        zms.deleteTopLevelDomain(mockDomRsrcCtx, "coretech", auditRef);
        zms.deleteTopLevelDomain(mockDomRsrcCtx,domainName, auditRef);
    }

    @Test
    public void testUpdateGroup() {

        final String domainName = "update-group";
        final String groupName = "group1";

        TopLevelDomain dom1 = createTopLevelDomainObject(domainName, "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        ArrayList<GroupMember> groupMembers = new ArrayList<>();
        groupMembers.add(new GroupMember().setMemberName("user.user1"));
        groupMembers.add(new GroupMember().setMemberName("user.user2"));
        groupMembers.add(new GroupMember().setMemberName("user.user3"));

        Group group1 = createGroupObject(domainName, groupName, groupMembers);
        zms.putGroup(mockDomRsrcCtx, domainName, groupName, auditRef, group1);

        // now let's update our group with new members

        groupMembers = new ArrayList<>();
        groupMembers.add(new GroupMember().setMemberName("user.user3"));
        groupMembers.add(new GroupMember().setMemberName("user.user4"));
        groupMembers.add(new GroupMember().setMemberName("user.user5"));

        zms.putGroup(mockDomRsrcCtx, domainName, groupName, auditRef, group1);

        // now let's get the group and verify our update

        Group group = zms.getGroup(mockDomRsrcCtx, domainName, groupName, false, false);
        assertNotNull(group);

        assertEquals(group.getName(), domainName + ":group.Group1".toLowerCase());
        List<GroupMember> members = group.getGroupMembers();
        assertNotNull(members);
        assertEquals(members.size(), 3);
        List<String> checkList = new ArrayList<>();
        checkList.add("user.user3");
        checkList.add("user.user4");
        checkList.add("user.user5");
        checkGroupMember(checkList, members);

        zms.deleteTopLevelDomain(mockDomRsrcCtx,domainName, auditRef);
    }

    @Test
    public void testUpdateReviewEnabledRole() {

        final String domainName = "update-review-group";
        final String groupName = "group1";

        TopLevelDomain dom1 = createTopLevelDomainObject(domainName, "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        Group group1 = createGroupObject(domainName, groupName, null);
        group1.setReviewEnabled(true);
        zms.putGroup(mockDomRsrcCtx, domainName, groupName, auditRef, group1);

        // now let's update our group with new members

        group1 = createGroupObject(domainName, groupName, "user.user1", "user.user2");
        try {
            zms.putGroup(mockDomRsrcCtx, domainName, groupName, auditRef, group1);
            fail();
        } catch (ResourceException ex) {
            assertTrue(ex.getMessage().contains("reviewEnabled groups"));
        }

        zms.deleteTopLevelDomain(mockDomRsrcCtx,domainName, auditRef);
    }

    @Test
    public void testCreateNormalizedCombinedMemberGroup() {

        final String domainName = "norm-combined-member-group";
        final String groupName = "group1";

        TopLevelDomain dom1 = createTopLevelDomainObject(domainName, "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        TopLevelDomain dom2 = createTopLevelDomainObject("coretech",
                "Test Domain2", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom2);

        SubDomain subDom2 = createSubDomainObject("storage", "coretech",
                "Test Domain2", "testOrg", adminUser);
        zms.postSubDomain(mockDomRsrcCtx, "coretech", auditRef, subDom2);

        SubDomain subDom3 = createSubDomainObject("user1", "user",
                "Test Domain2", "testOrg", adminUser);
        zms.postSubDomain(mockDomRsrcCtx, "user", auditRef, subDom3);

        SubDomain subDom4 = createSubDomainObject("dom1", "user.user1",
                "Test Domain2", "testOrg", adminUser);
        zms.postSubDomain(mockDomRsrcCtx, "user.user1", auditRef, subDom4);

        ArrayList<GroupMember> groupMembers = new ArrayList<>();
        groupMembers.add(new GroupMember().setMemberName("user.joe"));
        groupMembers.add(new GroupMember().setMemberName("user.joe"));
        groupMembers.add(new GroupMember().setMemberName("user.joe"));
        groupMembers.add(new GroupMember().setMemberName("user.jane"));
        groupMembers.add(new GroupMember().setMemberName("coretech.storage"));
        groupMembers.add(new GroupMember().setMemberName("coretech.storage"));
        groupMembers.add(new GroupMember().setMemberName("user.user1.dom1.api"));

        Group group1 = createGroupObject(domainName, groupName, groupMembers);
        zms.putGroup(mockDomRsrcCtx, domainName, groupName, auditRef, group1);

        Group group = zms.getGroup(mockDomRsrcCtx, domainName, groupName, false, false);
        assertNotNull(group);

        assertEquals(group.getName(), domainName + ":group.group1".toLowerCase());
        List<GroupMember> members = group.getGroupMembers();
        assertNotNull(members);
        assertEquals(members.size(), 4);
        List<String> checkList = new ArrayList<>();
        checkList.add("user.joe");
        checkList.add("user.jane");
        checkList.add("coretech.storage");
        checkList.add("user.user1.dom1.api");
        checkGroupMember(checkList, members);

        zms.deleteSubDomain(mockDomRsrcCtx, "user.user1", "dom1", auditRef);
        zms.deleteSubDomain(mockDomRsrcCtx, "user", "user1", auditRef);
        zms.deleteSubDomain(mockDomRsrcCtx, "coretech", "storage", auditRef);
        zms.deleteTopLevelDomain(mockDomRsrcCtx, "coretech", auditRef);
        zms.deleteTopLevelDomain(mockDomRsrcCtx,domainName, auditRef);
    }

    @Test
    public void testDeleteGroup() {

        final String domainName = "delete-group";
        final String groupName1 = "group1";
        final String groupName2 = "group2";

        TopLevelDomain dom1 = createTopLevelDomainObject(domainName, "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        Group group1 = createGroupObject(domainName, groupName1, "user.joe", "user.jane");
        zms.putGroup(mockDomRsrcCtx, domainName, groupName1, auditRef, group1);

        Group group2 = createGroupObject(domainName, groupName2, "user.joe", "user.jane");
        zms.putGroup(mockDomRsrcCtx, domainName, groupName2, auditRef, group2);

        Groups groupList = zms.getGroups(mockDomRsrcCtx, domainName, false);
        assertNotNull(groupList);

        assertEquals(groupList.getList().size(), 2);

        zms.deleteGroup(mockDomRsrcCtx, domainName, groupName1, auditRef);

        groupList = zms.getGroups(mockDomRsrcCtx, domainName, false);
        assertNotNull(groupList);

        assertEquals(groupList.getList().size(), 1);

        assertEquals(groupList.getList().get(0).getName(), domainName + ":group.group2");
        zms.deleteTopLevelDomain(mockDomRsrcCtx, domainName, auditRef);
    }

    @Test
    public void testGetGroups() {

        final String domainName = "get-groups";
        final String groupName1 = "group1";

        TopLevelDomain dom1 = createTopLevelDomainObject(domainName, "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        Group group1 = createGroupObject(domainName, groupName1, "user.joe", "user.jane");
        zms.putGroup(mockDomRsrcCtx, domainName, groupName1, auditRef, group1);

        Groups groupList = zms.getGroups(mockDomRsrcCtx, domainName, true);
        assertNotNull(groupList);

        assertEquals(groupList.getList().size(), 1);

        Group group = groupList.list.get(0);
        assertEquals(group.getName(), domainName + ":group.group1");
        assertEquals(group.getGroupMembers().size(), 2);
        List<String> checkList = new ArrayList<>();
        checkList.add("user.joe");
        checkList.add("user.jane");
        checkGroupMember(checkList, group.getGroupMembers());

        // get groups on unknown domain

        try {
            zms.getGroups(mockDomRsrcCtx, "unknown-domain", true);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), ResourceException.NOT_FOUND);
        }

        zms.deleteTopLevelDomain(mockDomRsrcCtx, domainName, auditRef);
    }

    @Test
    public void testDeleteGroupMissingAuditRef() {

        final String domainName = "delete-group-missing-ref";
        final String groupName = "group1";

        TopLevelDomain dom = createTopLevelDomainObject(domainName, "Test Domain1", "testOrg", adminUser);
        dom.setAuditEnabled(true);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom);

        Group group = createGroupObject(domainName, groupName, "user.joe", "user.jane");
        zms.putGroup(mockDomRsrcCtx, domainName, groupName, auditRef, group);

        try {
            zms.deleteGroup(mockDomRsrcCtx, domainName, groupName, null);
            fail("requesterror not thrown by deleteGroup.");
        } catch (ResourceException ex) {
            assertEquals(400, ex.getCode());
            assertTrue(ex.getMessage().contains("Audit reference required"));
        } finally {
            zms.deleteTopLevelDomain(mockDomRsrcCtx, domainName, auditRef);
        }
    }

    @Test
    public void testDeleteGroupThrowException() {

        TestAuditLogger alogger = new TestAuditLogger();
        ZMSImpl zmsImpl = getZmsImpl(alogger);

        final String domainName = "DomainName1";
        final String groupName = "GroupName1";
        try {
            zmsImpl.deleteGroup(mockDomRsrcCtx,domainName, groupName, auditRef);
            fail("notfounderror not thrown.");
        } catch (ResourceException e) {
            assertEquals(e.getCode(), 404);
        }
    }

    @Test
    public void testConvertToLowerGroupMember() {

        AthenzObject.GROUP_MEMBER.convertToLowerCase(null);

        List<GroupMember> list = new ArrayList<>();
        list.add(new GroupMember().setGroupName("GroupA").setMemberName("MemberA").setDomainName("Domain"));
        AthenzObject.GROUP_MEMBER.convertToLowerCase(list);

        GroupMember member = list.get(0);
        assertEquals(member.getMemberName(), "membera");
        assertEquals(member.getGroupName(), "groupa");
        assertEquals(member.getDomainName(), "domain");

        list = new ArrayList<>();
        list.add(new GroupMember().setGroupName("GroupA").setDomainName("Domain"));
        AthenzObject.GROUP_MEMBER.convertToLowerCase(list);

        member = list.get(0);
        assertNull(member.getMemberName());
        assertEquals(member.getGroupName(), "groupa");
        assertEquals(member.getDomainName(), "domain");
    }

    @Test
    public void testConvertToLowerGroupMembership() {

        GroupMembership member = new GroupMembership().setGroupName("GroupA").setMemberName("MemberA");
        AthenzObject.GROUP_MEMBERSHIP.convertToLowerCase(member);

        assertEquals(member.getMemberName(), "membera");
        assertEquals(member.getGroupName(), "groupa");

        member = new GroupMembership().setMemberName("MemberA");
        AthenzObject.GROUP_MEMBERSHIP.convertToLowerCase(member);

        assertEquals(member.getMemberName(), "membera");
        assertNull(member.getGroupName());
    }

    @Test
    public void testConvertToLowerGroupMeta() {

        GroupMeta meta = new GroupMeta().setNotifyRoles("rolesA,rolesB");
        AthenzObject.GROUP_META.convertToLowerCase(meta);

        assertEquals(meta.getNotifyRoles(), "rolesa,rolesb");

        meta = new GroupMeta();
        AthenzObject.GROUP_META.convertToLowerCase(meta);

        assertNull(meta.getNotifyRoles());
    }

    @Test
    public void testIsConsistentGroupName() {

        Group group = new Group();

        group.setName("domain1:group.group1");
        assertTrue(zms.isConsistentGroupName("domain1", "group1", group));

        // local name behavior

        group.setName("group1");
        assertTrue(zms.isConsistentGroupName("domain1", "group1", group));
        assertEquals(group.getName(), "domain1:group.group1");

        // inconsistent behavior

        group.setName("domain1:group.group1");
        assertFalse(zms.isConsistentGroupName("domain1", "group2", group));

        group.setName("role1");
        assertFalse(zms.isConsistentGroupName("domain1", "group2", group));
    }

    @Test
    public void testNormalizeGroupMembersNull() {
        Group group = new Group();
        zms.normalizeGroupMembers(group);
        assertTrue(group.getGroupMembers().isEmpty());
    }

    @Test
    public void testValidateGroupMemberPrincipals() {

        zms.validateUserRoleMembers = false;
        zms.validateServiceRoleMembers = false;

        // if both are false then any invalid users are ok

        List<GroupMember> groupMembers = new ArrayList<>();
        groupMembers.add(new GroupMember().setMemberName("user"));
        groupMembers.add(new GroupMember().setMemberName("user.john"));
        groupMembers.add(new GroupMember().setMemberName("user.jane"));
        groupMembers.add(new GroupMember().setMemberName("coretech.api"));
        groupMembers.add(new GroupMember().setMemberName("coretech.backend"));

        Group group = new Group().setGroupMembers(groupMembers);
        zms.validateGroupMemberPrincipals(group, null, "unittest");

        // enable user authority check

        zms.userAuthority = new TestUserPrincipalAuthority();
        zms.validateUserRoleMembers = true;

        // include all valid principals

        groupMembers = new ArrayList<>();
        groupMembers.add(new GroupMember().setMemberName("user.joe"));
        groupMembers.add(new GroupMember().setMemberName("user.jane"));
        group.setGroupMembers(groupMembers);

        zms.validateGroupMemberPrincipals(group, null, "unittest");

        // add one more invalid user

        groupMembers.add(new GroupMember().setMemberName("user.john"));
        try {
            zms.validateGroupMemberPrincipals(group, null, "unittest");
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), ResourceException.BAD_REQUEST);
        }
    }

    @Test
    public void testUpdateGroupMemberUserAuthorityExpiry() {

        Group group = new Group().setUserAuthorityExpiration("elevated-clearance");

        List<GroupMember> members = new ArrayList<>();
        members.add(new GroupMember().setMemberName("user.john"));
        members.add(new GroupMember().setMemberName("user.joe"));
        group.setGroupMembers(members);

        Authority savedAuthority = zms.userAuthority;
        zms.userAuthority = null;

        // with authority null we always get no changes

        zms.updateGroupMemberUserAuthorityExpiry(group, "unit-test");
        assertNull(group.getGroupMembers().get(0).getExpiration());
        assertNull(group.getGroupMembers().get(1).getExpiration());

        Authority authority = Mockito.mock(Authority.class);
        Mockito.when(authority.getDateAttribute("user.john", "elevated-clearance"))
                .thenReturn(new Date());
        Mockito.when(authority.getDateAttribute("user.jane", "elevated-clearance"))
                .thenReturn(new Date());
        Mockito.when(authority.getDateAttribute("user.joe", "elevated-clearance"))
                .thenReturn(null);
        zms.userAuthority = authority;

        // with one valid and one invalid we should get an exception

        try {
            zms.updateGroupMemberUserAuthorityExpiry(group, "unit-test");
            fail();
        } catch (ResourceException ex) {
            assertTrue(ex.getMessage().contains("Invalid member: user.joe"));
        }

        // let's have one valid user and one service

        members = new ArrayList<>();
        members.add(new GroupMember().setMemberName("user.john"));
        members.add(new GroupMember().setMemberName("sports.api"));
        group.setGroupMembers(members);

        // the user will have an expiration while service is skipped

        zms.updateGroupMemberUserAuthorityExpiry(group, "unit-test");
        assertNotNull(group.getGroupMembers().get(0).getExpiration());
        assertNull(group.getGroupMembers().get(1).getExpiration());

        // now let's have only user members

        members = new ArrayList<>();
        members.add(new GroupMember().setMemberName("user.john"));
        members.add(new GroupMember().setMemberName("user.jane"));
        group.setGroupMembers(members);

        zms.updateGroupMemberUserAuthorityExpiry(group, "unit-test");
        assertNotNull(group.getGroupMembers().get(0).getExpiration());
        assertNotNull(group.getGroupMembers().get(1).getExpiration());

        zms.userAuthority = savedAuthority;
    }

    @Test
    public void testGetGroupMembership() {

        final String domainName = "get-group-mbr";
        final String groupName = "group1";

        TopLevelDomain dom1 = createTopLevelDomainObject(domainName, "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        // inconsistent group name

        Group group1 = createGroupObject(domainName, groupName, "user.joe", "user.jane");
        zms.putGroup(mockDomRsrcCtx, domainName, groupName, auditRef, group1);

        GroupMembership mbr = zms.getGroupMembership(mockDomRsrcCtx, domainName, groupName, "user.joe", null);
        assertNotNull(mbr);
        assertTrue(mbr.getIsMember());
        assertTrue(mbr.getApproved());
        assertEquals(mbr.getGroupName(), domainName + ":group." + groupName);
        assertEquals(mbr.getMemberName(), "user.joe");

        mbr = zms.getGroupMembership(mockDomRsrcCtx, domainName, groupName, "user.jane", null);
        assertNotNull(mbr);
        assertTrue(mbr.getIsMember());
        assertTrue(mbr.getApproved());
        assertEquals(mbr.getGroupName(), domainName + ":group." + groupName);
        assertEquals(mbr.getMemberName(), "user.jane");

        zms.deleteTopLevelDomain(mockDomRsrcCtx,domainName, auditRef);
    }

    @Test
    public void testPutGroupMembership() {

        final String domainName = "put-group-mbr";
        final String groupName = "group1";

        Mockito.when(mockDomRsrcCtx.getApiName())
                .thenReturn("putserviceidentity")
                .thenReturn("posttopleveldomain")
                .thenReturn("posttopleveldomain")
                .thenReturn("postsubdomain")
                .thenReturn("putgroup").thenReturn("putgroup").thenReturn("putgroup").thenReturn("putgroup") // called 4 times in group api
                .thenReturn("putgroupmembership");

        TestAuditLogger alogger = new TestAuditLogger();
        ZMSImpl zmsImpl = getZmsImpl(alogger);

        TopLevelDomain dom1 = createTopLevelDomainObject(domainName, "Test Domain1", "testOrg", "user.user1");
        zmsImpl.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        TopLevelDomain dom2 = createTopLevelDomainObject("coretech", "Test Domain2", "testOrg", adminUser);
        zmsImpl.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom2);

        SubDomain subDom2 = createSubDomainObject("storage", "coretech", "Test Domain2", "testOrg", adminUser);
        zmsImpl.postSubDomain(mockDomRsrcCtx, "coretech", auditRef, subDom2);

        Group group1 = createGroupObject(domainName, groupName, "user.joe", "user.jane");
        zmsImpl.putGroup(mockDomRsrcCtx, domainName, groupName, auditRef, group1);

        GroupMembership mbr = generateGroupMembership(groupName, "user.doe");
        zmsImpl.putGroupMembership(mockDomRsrcCtx, domainName, groupName, "user.doe", auditRef, mbr);

        // check audit log msg for putGroup
        boolean foundError = false;
        List<String> aLogMsgs = alogger.getLogMsgList();
        System.err.println("testPutGroupMembership: Number of lines: " + aLogMsgs.size());
        for (String msg: aLogMsgs) {
            if (!msg.contains("WHAT-api=(putgroupmembership)")) {
                continue;
            }
            int index = msg.indexOf("WHAT-details=(");
            assertTrue(index != -1, msg);
            int index2 = msg.indexOf("{\"member\": \"user.doe\", \"approved\": true, \"system-disabled\": 0}");
            assertTrue(index2 > index, msg);
            foundError = true;
            break;
        }
        assertTrue(foundError);

        aLogMsgs.clear();
        mbr = generateGroupMembership(groupName, "coretech.storage");
        zmsImpl.putGroupMembership(mockDomRsrcCtx, domainName, groupName, "coretech.storage", auditRef, mbr);

        Group group = zmsImpl.getGroup(mockDomRsrcCtx, domainName, groupName, false, false);
        assertNotNull(group);

        List<GroupMember> members = group.getGroupMembers();
        assertNotNull(members);
        assertEquals(members.size(), 4);

        List<String> checkList = new ArrayList<>();
        checkList.add("user.joe");
        checkList.add("user.jane");
        checkList.add("user.doe");
        checkList.add("coretech.storage");
        checkGroupMember(checkList, members);

        foundError = false;
        System.err.println("testGroupPutMembership: now Number of lines: " + aLogMsgs.size());
        for (String msg: aLogMsgs) {
            if (!msg.contains("WHAT-api=(putgroupmembership)")) {
                continue;
            }
            int index = msg.indexOf("WHAT-details=(");
            assertTrue(index != -1, msg);
            int index2 = msg.indexOf("{\"member\": \"coretech.storage\", \"approved\": true, \"system-disabled\": 0}");
            assertTrue(index2 > index, msg);
            foundError = true;
            break;
        }
        assertTrue(foundError);

        // enable user validation for the test

        zmsImpl.userAuthority = new TestUserPrincipalAuthority();
        zmsImpl.validateUserRoleMembers = true;

        // valid users no exception

        mbr = generateGroupMembership(groupName, "user.joe");
        zmsImpl.putGroupMembership(mockDomRsrcCtx, domainName, groupName, "user.joe", auditRef, mbr);

        // invalid user with exception

        mbr = generateGroupMembership("group1", "user.john");
        try {
            zmsImpl.putGroupMembership(mockDomRsrcCtx, domainName, groupName, "user.john", auditRef, mbr);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), ResourceException.BAD_REQUEST);
        }

        zmsImpl.deleteSubDomain(mockDomRsrcCtx, "coretech", "storage", auditRef);
        zmsImpl.deleteTopLevelDomain(mockDomRsrcCtx, "coretech", auditRef);
        zmsImpl.deleteTopLevelDomain(mockDomRsrcCtx, domainName, auditRef);
    }

    @Test
    public void testPutGroupMembershipWithElevatedClearance() {

        final String domainName = "put-group-mbr-expiry";
        final String groupName = "group1";

        TopLevelDomain dom1 = createTopLevelDomainObject(domainName, "Test Domain1", "testOrg", "user.user1");
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        Date joeDate = new Date();
        Date janeDate = new Date();
        Date bobDate = new Date();

        Set<String> attrSet = new HashSet<>();
        attrSet.add("ElevatedClearance");

        Authority mockAuthority = Mockito.mock(Authority.class);
        Mockito.when(mockAuthority.getDateAttribute("user.joe", "ElevatedClearance")).thenReturn(joeDate);
        Mockito.when(mockAuthority.getDateAttribute("user.jane", "ElevatedClearance")).thenReturn(janeDate);
        Mockito.when(mockAuthority.getDateAttribute("user.bob", "ElevatedClearance")).thenReturn(bobDate);
        Mockito.when(mockAuthority.getDateAttribute("user.dave", "ElevatedClearance")).thenReturn(null);
        Mockito.when(mockAuthority.dateAttributesSupported()).thenReturn(attrSet);

        Authority savedAuthority = zms.userAuthority;
        zms.userAuthority = mockAuthority;

        Group group1 = createGroupObject(domainName, groupName, "user.joe", "user.jane");
        group1.setUserAuthorityExpiration("ElevatedClearance");
        zms.putGroup(mockDomRsrcCtx, domainName, groupName, auditRef, group1);

        GroupMembership mbr = zms.getGroupMembership(mockDomRsrcCtx, domainName, groupName, "user.joe", null);
        assertNotNull(mbr);
        assertEquals(mbr.getExpiration().millis(), joeDate.getTime());

        mbr = zms.getGroupMembership(mockDomRsrcCtx, domainName, groupName, "user.jane", null);
        assertNotNull(mbr);
        assertEquals(mbr.getExpiration().millis(), janeDate.getTime());

        mbr = generateGroupMembership(groupName, "user.bob");
        zms.putGroupMembership(mockDomRsrcCtx, domainName, groupName, "user.bob", auditRef, mbr);
        mbr = zms.getGroupMembership(mockDomRsrcCtx, domainName, groupName, "user.bob", null);
        assertNotNull(mbr);
        assertEquals(mbr.getExpiration().millis(), bobDate.getTime());

        mbr = generateGroupMembership(groupName, "user.dave");
        try {
            zms.putGroupMembership(mockDomRsrcCtx, domainName, groupName, "user.dave", auditRef, mbr);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), ResourceException.BAD_REQUEST);
            assertTrue(ex.getMessage().contains("does not have required user authority expiry configured"));
        }

        mbr = generateGroupMembership(groupName, "sys.auth.zts");
        zms.putGroupMembership(mockDomRsrcCtx, domainName, groupName, "sys.auth.zts", auditRef, mbr);
        mbr = zms.getGroupMembership(mockDomRsrcCtx, domainName, groupName, "sys.auth.zts", null);
        assertNotNull(mbr);

        zms.userAuthority = savedAuthority;
        zms.deleteTopLevelDomain(mockDomRsrcCtx, domainName, auditRef);
    }

    @Test
    public void testPutGroupMembershipForbidden() {

        final String domainName = "put-group-mbr-403";
        final String groupName = "group1";

        TopLevelDomain dom1 = createTopLevelDomainObject(domainName, "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        Group group1 = createGroupObject(domainName, groupName, "user.joe", "user.jane");
        zms.putGroup(mockDomRsrcCtx, domainName, groupName, auditRef, group1);

        GroupMembership mbr = generateGroupMembership(groupName, "user.doe");
        // this should be rejected since user.user1 is not domain admin
        try {
            zms.putGroupMembership(mockDomRsrcCtx, domainName, groupName, "user.doe", auditRef, mbr);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), ResourceException.FORBIDDEN);
        }

        zms.deleteTopLevelDomain(mockDomRsrcCtx, domainName, auditRef);
    }

    @Test
    public void testPutGroupMembershipSelfServe() {

        final String domainName = "put-group-mbr-self-serve";
        final String groupName = "group1";

        TopLevelDomain dom1 = createTopLevelDomainObject(domainName, "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        Group group1 = createGroupObject(domainName, groupName, "user.joe", "user.jane");
        group1.setSelfServe(true);
        zms.putGroup(mockDomRsrcCtx, domainName, groupName, auditRef, group1);

        GroupMembership mbr = generateGroupMembership(groupName, "user.doe");

        // since we have admin mismatch it should be added as pending
        // since self-serve flag is on

        zms.putGroupMembership(mockDomRsrcCtx, domainName, groupName, "user.doe", auditRef, mbr);

        GroupMembership mbr1 = zms.getGroupMembership(mockDomRsrcCtx, domainName, groupName, "user.doe", null);
        assertNotNull(mbr1);
        assertFalse(mbr1.getApproved());

        // now we're going to delete our pending group membership
        // this should be allowed since the user itself is the requestor

        zms.deletePendingGroupMembership(mockDomRsrcCtx, domainName, groupName, "user.doe", null);

        zms.deleteTopLevelDomain(mockDomRsrcCtx, domainName, auditRef);
    }

    @Test
    public void testDeleteGroupMembershipForbidden() {

        final String domainName = "del-group-mbr-403";
        final String groupName = "group1";

        TopLevelDomain dom1 = createTopLevelDomainObject(domainName, "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        Group group1 = createGroupObject(domainName, groupName, "user.joe", "user.jane");
        group1.setSelfServe(true);
        zms.putGroup(mockDomRsrcCtx, domainName, groupName, auditRef, group1);

        GroupMembership mbr = generateGroupMembership(groupName, "user.doe");

        // since we have admin mismatch it should be added as pending
        // since self-serve flag is on

        zms.putGroupMembership(mockDomRsrcCtx, domainName, groupName, "user.doe", auditRef, mbr);

        // first deleting an invalid domain should return 404

        try {
            zms.deletePendingGroupMembership(mockDomRsrcCtx, "unknown-domain", groupName, "user.doe", null);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), ResourceException.NOT_FOUND);
        }

        // now we're going to add with a different user as admin
        // which should be rejected

        Authority auditAdminPrincipalAuthority = new com.yahoo.athenz.common.server.debug.DebugPrincipalAuthority();
        String auditAdminUnsignedCreds = "v=U1;d=user;n=fury";
        // used with the mockDomRestRsrcCtx
        final Principal rsrcAuditAdminPrince = SimplePrincipal.create("user", "fury",
                auditAdminUnsignedCreds + ";s=signature", 0, auditAdminPrincipalAuthority);
        assertNotNull(rsrcAuditAdminPrince);
        ((SimplePrincipal) rsrcAuditAdminPrince).setUnsignedCreds(auditAdminUnsignedCreds);

        Mockito.when(mockDomRestRsrcCtx.principal()).thenReturn(rsrcAuditAdminPrince);
        Mockito.when(mockDomRsrcCtx.principal()).thenReturn(rsrcAuditAdminPrince);

        try {
            zms.deletePendingGroupMembership(mockDomRsrcCtx, domainName, groupName, "user.doe", null);
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), ResourceException.FORBIDDEN);
        }

        //revert back to admin principal
        Authority adminPrincipalAuthority = new com.yahoo.athenz.common.server.debug.DebugPrincipalAuthority();
        String adminUnsignedCreds = "v=U1;d=user;n=user1";
        // used with the mockDomRestRsrcCtx
        final Principal rsrcAdminPrince = SimplePrincipal.create("user", "user1",
                adminUnsignedCreds + ";s=signature", 0, adminPrincipalAuthority);
        assertNotNull(rsrcAdminPrince);
        ((SimplePrincipal) rsrcAdminPrince).setUnsignedCreds(adminUnsignedCreds);

        zms.deleteTopLevelDomain(mockDomRsrcCtx, domainName, auditRef);
    }

    @Test
    public void testPutGroupMembershipExceptions() {

        final String domainName = "put-group-mbr-ex";
        final String groupName = "group1";

        TopLevelDomain dom1 = createTopLevelDomainObject(domainName, "Test Domain1", "testOrg", "user.user1");
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        Group group1 = createGroupObject(domainName, groupName, "user.joe", "user.jane");
        zms.putGroup(mockDomRsrcCtx, domainName, groupName, auditRef, group1);

        // member mismatch

        GroupMembership mbr = generateGroupMembership(groupName, "user.doe");
        try {
            zms.putGroupMembership(mockDomRsrcCtx, domainName, groupName, "user.joe", auditRef, mbr);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), ResourceException.BAD_REQUEST);
        }

        // groupname mismatch

        try {
            zms.putGroupMembership(mockDomRsrcCtx, domainName, "invalid-group", "user.doe", auditRef, mbr);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), ResourceException.BAD_REQUEST);
        }

        // invalid group

        mbr = generateGroupMembership("invalid-group", "user.doe");
        try {
            zms.putGroupMembership(mockDomRsrcCtx, domainName, "invalid-group", "user.doe", auditRef, mbr);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), ResourceException.BAD_REQUEST);
        }

        zms.deleteTopLevelDomain(mockDomRsrcCtx, domainName, auditRef);
    }

    @Test
    public void testPutGroupMembershipDecisionAuditEnabledGroupByDomain() {

        final String domainName = "group-dec-by-domain";
        final String groupName = "group1";

        TopLevelDomain dom1 = createTopLevelDomainObject(domainName, "Approval Test Domain1",
                "testOrg", "user.user1");
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);
        DomainMeta meta = createDomainMetaObject("Domain Meta for approval test", "testOrg",
                true, true, "12345", 1001);
        zms.putDomainMeta(mockDomRsrcCtx, domainName, auditRef, meta);
        zms.putDomainSystemMeta(mockDomRsrcCtx, domainName, "auditenabled", auditRef, meta);

        Group auditedGroup = createGroupObject(domainName, groupName, "user.john", "user.jane");
        zms.putGroup(mockDomRsrcCtx, domainName, groupName, auditRef, auditedGroup);
        GroupSystemMeta rsm = createGroupSystemMetaObject(true);
        zms.putGroupSystemMeta(mockDomRsrcCtx, domainName, groupName, "auditenabled", auditRef, rsm);

        GroupMembership mbr = new GroupMembership();
        mbr.setMemberName("user.bob");
        mbr.setActive(false);
        mbr.setApproved(false);
        zms.putGroupMembership(mockDomRsrcCtx, domainName, groupName, "user.bob", auditRef, mbr);

        Group resgroup = zms.getGroup(mockDomRsrcCtx, domainName, groupName, false, true);
        assertEquals(resgroup.getGroupMembers().size(), 3);
        for (GroupMember rmem : resgroup.getGroupMembers()) {
            if ("user.bob".equals(rmem.getMemberName())) {
                assertFalse(rmem.getApproved());
            }
        }

        setupPrincipalAuditedRoleApprovalByDomain(zms, "user.fury", domainName);

        mbr = new GroupMembership();
        mbr.setMemberName("user.bob");
        mbr.setActive(true);
        mbr.setApproved(true);

        try {
            zms.putGroupMembershipDecision(mockDomRsrcCtx, domainName, groupName, "user.bob", auditRef, mbr);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), ResourceException.FORBIDDEN);
        }

        Authority auditAdminPrincipalAuthority = new com.yahoo.athenz.common.server.debug.DebugPrincipalAuthority();
        String auditAdminUnsignedCreds = "v=U1;d=user;n=fury";
        // used with the mockDomRestRsrcCtx
        final Principal rsrcAuditAdminPrince = SimplePrincipal.create("user", "fury",
                auditAdminUnsignedCreds + ";s=signature", 0, auditAdminPrincipalAuthority);
        assertNotNull(rsrcAuditAdminPrince);
        ((SimplePrincipal) rsrcAuditAdminPrince).setUnsignedCreds(auditAdminUnsignedCreds);

        Mockito.when(mockDomRestRsrcCtx.principal()).thenReturn(rsrcAuditAdminPrince);
        Mockito.when(mockDomRsrcCtx.principal()).thenReturn(rsrcAuditAdminPrince);

        zms.putGroupMembershipDecision(mockDomRsrcCtx, domainName, groupName, "user.bob", auditRef, mbr);

        //revert back to admin principal
        Authority adminPrincipalAuthority = new com.yahoo.athenz.common.server.debug.DebugPrincipalAuthority();
        String adminUnsignedCreds = "v=U1;d=user;n=user1";
        // used with the mockDomRestRsrcCtx
        final Principal rsrcAdminPrince = SimplePrincipal.create("user", "user1",
                adminUnsignedCreds + ";s=signature", 0, adminPrincipalAuthority);
        assertNotNull(rsrcAdminPrince);
        ((SimplePrincipal) rsrcAdminPrince).setUnsignedCreds(adminUnsignedCreds);

        Mockito.when(mockDomRestRsrcCtx.principal()).thenReturn(rsrcAdminPrince);
        Mockito.when(mockDomRsrcCtx.principal()).thenReturn(rsrcAdminPrince);

        resgroup = zms.getGroup(mockDomRsrcCtx, domainName, groupName, false, false);
        assertEquals(resgroup.getGroupMembers().size(), 3);
        for (GroupMember rmem : resgroup.getGroupMembers()) {
            if ("user.bob".equals(rmem.getMemberName())) {
                assertTrue(rmem.getApproved());
            }
        }

        clenaupPrincipalAuditedRoleApprovalByDomain(zms, domainName);
        zms.deleteTopLevelDomain(mockDomRsrcCtx, domainName, auditRef);
    }

    @Test
    public void testPutGroupMembershipDecisionAuditEnabledGroupInvalidUser() {

        final String domainName = "group-mbr-dec-invalid";
        final String groupName = "testgroup1";

        TopLevelDomain dom1 = createTopLevelDomainObject(domainName, "Approval Test Domain1",
                "testOrg", "user.user1");
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);
        DomainMeta meta = createDomainMetaObject("Domain Meta for approval test", "testOrg",
                true, true, "12345", 1001);
        zms.putDomainMeta(mockDomRsrcCtx, domainName, auditRef, meta);
        zms.putDomainSystemMeta(mockDomRsrcCtx, domainName, "auditenabled", auditRef, meta);

        Group auditedGroup = createGroupObject(domainName, groupName, "user.john", "user.jane");
        zms.putGroup(mockDomRsrcCtx, domainName, groupName, auditRef, auditedGroup);
        GroupSystemMeta rsm = createGroupSystemMetaObject(true);
        zms.putGroupSystemMeta(mockDomRsrcCtx, domainName, groupName, "auditenabled", auditRef, rsm);

        GroupMembership mbr = new GroupMembership();
        mbr.setMemberName("user.joe");
        mbr.setActive(false);
        mbr.setApproved(false);
        zms.putGroupMembership(mockDomRsrcCtx, domainName, groupName, "user.joe", auditRef, mbr);

        mbr = new GroupMembership();
        mbr.setMemberName("user.bob");
        mbr.setActive(false);
        mbr.setApproved(false);
        zms.putGroupMembership(mockDomRsrcCtx, domainName, groupName, "user.bob", auditRef, mbr);

        setupPrincipalAuditedRoleApprovalByOrg(zms, "user.fury", "testOrg");

        Authority auditAdminPrincipalAuthority = new com.yahoo.athenz.common.server.debug.DebugPrincipalAuthority();
        String auditAdminUnsignedCreds = "v=U1;d=user;n=fury";

        final Principal rsrcAuditAdminPrince = SimplePrincipal.create("user", "fury",
                auditAdminUnsignedCreds + ";s=signature", 0, auditAdminPrincipalAuthority);
        assertNotNull(rsrcAuditAdminPrince);
        ((SimplePrincipal) rsrcAuditAdminPrince).setUnsignedCreds(auditAdminUnsignedCreds);

        Mockito.when(mockDomRsrcCtx.principal()).thenReturn(rsrcAuditAdminPrince);

        // enable user authority check - joe and jane are the only
        // valid users in the system

        zms.userAuthority = new TestUserPrincipalAuthority();
        zms.validateUserRoleMembers = true;

        // first let's approve user.joe which should be ok since user joe
        // is a valid user based on our test authority

        mbr = new GroupMembership();
        mbr.setMemberName("user.joe");
        mbr.setActive(true);
        mbr.setApproved(true);
        zms.putGroupMembershipDecision(mockDomRsrcCtx, domainName, groupName, "user.joe", auditRef, mbr);

        // now let's approve our bob user which is going to be rejected
        // since bob is not a valid user based on our test authority

        mbr.setMemberName("user.bob");

        try {
            zms.putGroupMembershipDecision(mockDomRsrcCtx, domainName, groupName, "user.bob", auditRef, mbr);
            fail();
        }catch (ResourceException ex) {
            assertEquals(ex.code, 400);
        }

        // now let's just reject user bob which should work
        // ok because we no longer validate users when we
        // are rejecting thus deleting group members

        mbr.setActive(false);
        mbr.setApproved(false);
        zms.putGroupMembershipDecision(mockDomRsrcCtx, domainName, groupName, "user.bob", auditRef, mbr);

        clenaupPrincipalAuditedRoleApprovalByOrg(zms, "testOrg");
        zms.deleteTopLevelDomain(mockDomRsrcCtx, domainName, auditRef);
    }

    @Test
    public void testPutGroupMembershipDecisionReviewEnabledUnauthorized() {

        final String domainName = "group-review-enabled-domain-forbidden";
        final String groupName = "review-group";

        TopLevelDomain dom1 = createTopLevelDomainObject(domainName, "Approval test Domain1",
                "testOrg", "user.user1");
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        Group group1 = createGroupObject(domainName, groupName, null, null);
        zms.putGroup(mockDomRsrcCtx, domainName, groupName, auditRef, group1);

        GroupMeta rm = new GroupMeta().setReviewEnabled(true);
        zms.putGroupMeta(mockDomRsrcCtx, domainName, groupName, auditRef, rm);

        // add a user to the group

        GroupMembership mbr = new GroupMembership();
        mbr.setMemberName("user.bob");
        mbr.setActive(false);
        mbr.setApproved(false);

        zms.putGroupMembership(mockDomRsrcCtx, domainName, groupName, "user.bob", auditRef, mbr);

        // verify the user is added with pending state

        Group resgroup = zms.getGroup(mockDomRsrcCtx, domainName, groupName, false, true);
        assertEquals(resgroup.getGroupMembers().size(), 1);
        assertEquals(resgroup.getGroupMembers().get(0).getMemberName(), "user.bob");
        assertFalse(resgroup.getGroupMembers().get(0).getApproved());

        // now try as the second admin himself to approve this user and it must
        // be rejected since second admin is not authorized

        mbr = new GroupMembership();
        mbr.setMemberName("user.bob");
        mbr.setActive(true);
        mbr.setApproved(true);

        // switch to user.user2 principal to add a member to a group

        Authority principalAuthority = new com.yahoo.athenz.common.server.debug.DebugPrincipalAuthority();
        String unsignedCreds = "v=U1;d=user;n=user2";
        final Principal rsrcPrince = SimplePrincipal.create("user", "user2",
                unsignedCreds + ";s=signature", 0, principalAuthority);
        assertNotNull(rsrcPrince);
        ((SimplePrincipal) rsrcPrince).setUnsignedCreds(unsignedCreds);
        Mockito.when(mockDomRestRsrcCtx.principal()).thenReturn(rsrcPrince);
        Mockito.when(mockDomRsrcCtx.principal()).thenReturn(rsrcPrince);

        try {
            zms.putGroupMembershipDecision(mockDomRsrcCtx, domainName, groupName, "user.bob", auditRef, mbr);
            fail();
        } catch (ResourceException ex) {
            assertTrue(ex.getMessage().contains("not authorized to approve / reject members"));
        }

        // revert back to admin principal

        Authority adminPrincipalAuthority = new com.yahoo.athenz.common.server.debug.DebugPrincipalAuthority();
        String adminUnsignedCreds = "v=U1;d=user;n=user1";
        final Principal rsrcAdminPrince = SimplePrincipal.create("user", "user1",
                adminUnsignedCreds + ";s=signature", 0, adminPrincipalAuthority);
        assertNotNull(rsrcAdminPrince);
        ((SimplePrincipal) rsrcAdminPrince).setUnsignedCreds(adminUnsignedCreds);

        Mockito.when(mockDomRestRsrcCtx.principal()).thenReturn(rsrcAdminPrince);
        Mockito.when(mockDomRsrcCtx.principal()).thenReturn(rsrcAdminPrince);

        zms.deleteTopLevelDomain(mockDomRsrcCtx, domainName, auditRef);
    }

    @Test
    public void testPutGroupMembershipDecisionErrors() {

        final String domainName = "put-group-dec-errors";
        final String groupName = "testgroup1";

        TopLevelDomain dom1 = createTopLevelDomainObject(domainName,"Approval Test Domain1", "testOrg", "user.user1");
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);
        DomainMeta meta = createDomainMetaObject("Domain Meta for approval test", "testOrg",true, true, "12345", 1001);
        zms.putDomainMeta(mockDomRsrcCtx, domainName, auditRef, meta);
        zms.putDomainSystemMeta(mockDomRsrcCtx, domainName, "auditenabled", auditRef, meta);

        Group auditedGroup = createGroupObject(domainName, groupName,"user.john", "user.jane");
        zms.putGroup(mockDomRsrcCtx, domainName, groupName, auditRef, auditedGroup);
        GroupSystemMeta rsm = createGroupSystemMetaObject(true);
        zms.putGroupSystemMeta(mockDomRsrcCtx, domainName, groupName, "auditenabled", auditRef, rsm);

        GroupMembership mbr = new GroupMembership();
        mbr.setMemberName("user.bob");
        mbr.setActive(false);
        mbr.setApproved(false);
        zms.putGroupMembership(mockDomRsrcCtx, domainName, groupName, "user.bob", auditRef, mbr);

        mbr = new GroupMembership();
        mbr.setMemberName("user.bob");
        mbr.setActive(true);
        mbr.setApproved(true);

        try {
            zms.putGroupMembershipDecision(mockDomRsrcCtx, domainName, groupName, "user.chris", auditRef, mbr);//invalid member
            fail();
        } catch (ResourceException r) {
            assertEquals(r.code, 400);
            assertTrue(r.getMessage().contains("putGroupMembershipDecision: Member name in URI and GroupMembership object do not match"));
        }

        mbr.setGroupName("invalidgroup");
        try {
            zms.putGroupMembershipDecision(mockDomRsrcCtx, domainName, groupName, "user.bob", auditRef, mbr);//invalid group
            fail();
        } catch (ResourceException r) {
            assertEquals(r.code, 400);
            assertTrue(r.getMessage().contains("putGroupMembershipDecision: Group name in URI and GroupMembership object do not match"));
        }

        mbr.setGroupName(null);
        try {
            zms.putGroupMembershipDecision(mockDomRsrcCtx, "testdomain2", groupName, "user.bob", auditRef, mbr);//invalid domain name
            fail();
        } catch (ResourceException r) {
            assertEquals(r.code, 400);
            assertTrue(r.getMessage().contains("Invalid groupname"));
        }

        zms.deleteTopLevelDomain(mockDomRsrcCtx, domainName, auditRef);
    }

    @Test
    public void testPutGroupSystemMetaErrors() {

        final String domainName1 = "put-group-sys-meta-errors1";
        final String domainName2 = "put-group-sys-meta-errors2";
        final String groupName = "testgroup1";

        TopLevelDomain dom1 = createTopLevelDomainObject(domainName1, "Approval Test Domain1", "testOrg", "user.user1");
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);
        DomainMeta meta = createDomainMetaObject("Domain Meta for approval test", "testOrg",true, true, "12345", 1001);
        zms.putDomainMeta(mockDomRsrcCtx, domainName1, auditRef, meta);
        zms.putDomainSystemMeta(mockDomRsrcCtx, domainName1, "auditenabled", auditRef, meta);

        Group auditedGroup = createGroupObject(domainName1, groupName,"user.john", "user.jane");
        zms.putGroup(mockDomRsrcCtx, domainName1, groupName, auditRef, auditedGroup);

        // invalid field name

        GroupSystemMeta rsm = createGroupSystemMetaObject(true);
        try {
            zms.putGroupSystemMeta(mockDomRsrcCtx, domainName1, groupName, "unknown-field", auditRef, rsm);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), ResourceException.BAD_REQUEST);
            assertTrue(ex.getMessage().contains("unknown group system meta attribute"));
        }

        // invalid domain name

        try {
            zms.putGroupSystemMeta(mockDomRsrcCtx, "invalid-domain", groupName, "auditenabled", auditRef, rsm);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), ResourceException.NOT_FOUND);
        }

        // domain without audit enabled flag

        TopLevelDomain dom2 = createTopLevelDomainObject(domainName2, "Approval Test Domain1", "testOrg", "user.user1");
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom2);

        try {
            zms.putGroupSystemMeta(mockDomRsrcCtx, domainName2, groupName, "auditenabled", auditRef, rsm);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), ResourceException.BAD_REQUEST);
            assertTrue(ex.getMessage().contains("auditEnabled flag not set for domain"));
        }

        zms.deleteTopLevelDomain(mockDomRsrcCtx, domainName1, auditRef);
        zms.deleteTopLevelDomain(mockDomRsrcCtx, domainName2, auditRef);
    }

    @Test
    public void testDeleteGroupMembership() {

        final String domainName = "del-group-mbr";
        final String groupName = "group1";

        TopLevelDomain dom1 = createTopLevelDomainObject(domainName, "Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        Group group1 = createGroupObject(domainName, groupName, "user.joe", "user.jane");
        zms.putGroup(mockDomRsrcCtx, domainName, groupName, auditRef, group1);
        zms.deleteGroupMembership(mockDomRsrcCtx, domainName, groupName, "user.joe", auditRef);

        Group group = zms.getGroup(mockDomRsrcCtx, domainName, groupName, false, false);
        assertNotNull(group);

        List<GroupMember> members = group.getGroupMembers();
        assertNotNull(members);
        assertEquals(members.size(), 1);

        boolean found = false;
        for (GroupMember member: members) {
            if (member.getMemberName().equalsIgnoreCase("user.joe")) {
                fail("delete user.joe failed");
            }
            if (member.getMemberName().equalsIgnoreCase("user.jane")) {
                found = true;
            }
        }
        if (!found) {
            fail("user.jane not found");
        }

        zms.deleteTopLevelDomain(mockDomRsrcCtx, domainName, auditRef);
    }

    @Test
    public void testIsAllowedDeletePendingGroupMembership() {

        final String domainName = "allowed-del-pending-mbr";
        final String groupName = "group1";

        TopLevelDomain dom1 = createTopLevelDomainObject(domainName,
                "Group Test Domain1", "testOrg", "user.user1");
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        Group group1 = createGroupObject(domainName, groupName, "user.user1", "user.jane");
        zms.putGroup(mockDomRsrcCtx, domainName, groupName, auditRef, group1);

        Role role1 = createRoleObject(domainName, "testrole1", null, "user.user1", "user.jane");
        zms.putRole(mockDomRsrcCtx, domainName, "testrole1", auditRef, role1);

        Policy policy1 = createPolicyObject(domainName, "Policy1", "testrole1",
                "UPDATE", domainName + ":group.*", AssertionEffect.ALLOW);
        zms.putPolicy(mockDomRsrcCtx, domainName, "Policy1", auditRef, policy1);

        assertTrue(zms.isAllowedDeletePendingGroupMembership(mockDomRsrcCtx.principal(), domainName,
                groupName, "user.pending"));

        Authority principalAuthority = new com.yahoo.athenz.common.server.debug.DebugPrincipalAuthority();
        String unsignedCreds = "v=U1;d=user;n=jane";
        Principal rsrcPrince = SimplePrincipal.create("user", "jane", unsignedCreds + ";s=signature",0, principalAuthority);
        assertNotNull(rsrcPrince);
        ((SimplePrincipal) rsrcPrince).setUnsignedCreds(unsignedCreds);

        assertTrue(zms.isAllowedDeletePendingGroupMembership(rsrcPrince, domainName, groupName, "user.pending"));

        unsignedCreds = "v=U1;d=user;n=john";
        rsrcPrince = SimplePrincipal.create("user", "john", unsignedCreds + ";s=signature",0, principalAuthority);
        assertNotNull(rsrcPrince);
        ((SimplePrincipal) rsrcPrince).setUnsignedCreds(unsignedCreds);

        // this time false since john is not authorized

        assertFalse(zms.isAllowedDeletePendingGroupMembership(rsrcPrince, domainName,
                groupName, "user.pending"));

        zms.deleteTopLevelDomain(mockDomRsrcCtx, domainName, auditRef);
    }

    @Test
    public void testGPendingDomainGroupMembersListInvalidPrincipal() {

        try {
            zms.getPendingDomainGroupMembersList(mockDomRsrcCtx, "user.unknwon");
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), ResourceException.NOT_FOUND);
        }
    }

    @Test
    public void testDeletePendingGroupMembershipAdminRequest() {

        final String domainName = "delete-pending-admin";
        final String groupName = "testgroup1";

        TopLevelDomain dom1 = createTopLevelDomainObject(domainName, "delete pending membership",
                "testOrg", "user.user1");
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        setupPrincipalAuditedRoleApprovalByOrg(zms, "user.fury", "testorg");

        DomainMeta meta = createDomainMetaObject("Domain Meta for approval test", "testorg",
                true, true, "12345", 1001);
        zms.putDomainMeta(mockDomRsrcCtx, domainName, auditRef, meta);
        zms.putDomainSystemMeta(mockDomRsrcCtx, domainName, "auditenabled", auditRef, meta);
        setupPrincipalSystemMetaDelete(zms, mockDomRsrcCtx.principal().getFullName(), domainName, "org");
        zms.putDomainSystemMeta(mockDomRsrcCtx, domainName, "org", auditRef, meta);

        Group auditedGroup = createGroupObject(domainName, groupName, "user.john", "user.jane");
        zms.putGroup(mockDomRsrcCtx, domainName, groupName, auditRef, auditedGroup);
        GroupSystemMeta rsm = createGroupSystemMetaObject(true);
        zms.putGroupSystemMeta(mockDomRsrcCtx, domainName, groupName, "auditenabled", auditRef, rsm);

        GroupMembership mbr = new GroupMembership();
        mbr.setMemberName("user.bob");
        mbr.setActive(false);
        mbr.setApproved(false);
        zms.putGroupMembership(mockDomRsrcCtx, domainName, groupName, "user.bob", auditRef, mbr);

        // first request using admin principal

        DomainGroupMembership domainGroupMembership = zms.getPendingDomainGroupMembersList(mockDomRsrcCtx, "user.fury");

        assertNotNull(domainGroupMembership);
        assertNotNull(domainGroupMembership.getDomainGroupMembersList());
        assertEquals(domainGroupMembership.getDomainGroupMembersList().size(), 1);
        for (DomainGroupMembers drm : domainGroupMembership.getDomainGroupMembersList()) {
            assertEquals(drm.getDomainName(), domainName);
            assertNotNull(drm.getMembers());
            for (DomainGroupMember mem : drm.getMembers()) {
                assertNotNull(mem);
                assertEquals(mem.getMemberName(), "user.bob");
                for (GroupMember mr : mem.getMemberGroups()) {
                    assertNotNull(mr);
                    assertEquals(mr.getGroupName(), groupName);
                }
            }
        }

        Authority principalAuthority = new com.yahoo.athenz.common.server.debug.DebugPrincipalAuthority();
        String unsignedCreds = "v=U1;d=user;n=jane";
        Principal rsrcPrince = SimplePrincipal.create("user", "jane", unsignedCreds + ";s=signature",0, principalAuthority);
        assertNotNull(rsrcPrince);
        ((SimplePrincipal) rsrcPrince).setUnsignedCreds(unsignedCreds);
        ResourceContext ctx = createResourceContext(rsrcPrince);

        // first try to delete the pending request without proper authorization

        try {
            zms.deletePendingGroupMembership(ctx, domainName, groupName, "user.bob", auditRef);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 403);
        }

        // repeat the request using context principal

        zms.deletePendingGroupMembership(mockDomRsrcCtx, domainName, groupName, "user.bob", auditRef);

        // check the list to see there are no pending requests

        domainGroupMembership = zms.getPendingDomainGroupMembersList(mockDomRsrcCtx, "user.fury");
        assertNotNull(domainGroupMembership);
        assertTrue(domainGroupMembership.getDomainGroupMembersList().isEmpty());

        // delete some unknown member in the same group as admin

        try {
            zms.deletePendingGroupMembership(mockDomRsrcCtx, domainName, groupName, "user.bob2", auditRef);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 404);
        }

        // delete some member in an unknown domain

        try {
            zms.deletePendingGroupMembership(mockDomRsrcCtx, "unkwown-domain", groupName, "user.bob2", auditRef);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 404);
        }

        cleanupPrincipalSystemMetaDelete(zms);
        clenaupPrincipalAuditedRoleApprovalByOrg(zms, "testOrg");

        zms.deleteTopLevelDomain(mockDomRsrcCtx, domainName, auditRef);
    }

    @Test
    public void testDeletePendingGroupMembershipSelfServeRequest() {

        final String domainName = "delete-pending-self-serve";
        final String groupName = "group1";

        TopLevelDomain dom1 = createTopLevelDomainObject(domainName, "delete pending membership",
                "testOrg", "user.user1");
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        DomainMeta meta = createDomainMetaObject("Domain Meta for approval test", "testorg",
                true, false, "12345", 1001);
        zms.putDomainMeta(mockDomRsrcCtx, domainName, auditRef, meta);

        Group auditedGroup = createGroupObject(domainName, groupName, "user.john", "user.jane");
        zms.putGroup(mockDomRsrcCtx, domainName, groupName, auditRef, auditedGroup);
        GroupMeta rm = new GroupMeta().setSelfServe(true);
        zms.putGroupMeta(mockDomRsrcCtx, domainName, groupName, auditRef, rm);

        // user.joe is going to add user.bob in the self serve group

        Authority principalAuthority = new com.yahoo.athenz.common.server.debug.DebugPrincipalAuthority();
        String unsignedCreds = "v=U1;d=user;n=joe";
        Principal rsrcPrince = SimplePrincipal.create("user", "joe", unsignedCreds + ";s=signature",0, principalAuthority);
        assertNotNull(rsrcPrince);
        ((SimplePrincipal) rsrcPrince).setUnsignedCreds(unsignedCreds);
        ResourceContext ctxJoe = createResourceContext(rsrcPrince);

        GroupMembership mbr = new GroupMembership();
        mbr.setMemberName("user.bob");
        mbr.setActive(false);
        mbr.setApproved(false);
        zms.putGroupMembership(ctxJoe, domainName, groupName, "user.bob", auditRef, mbr);

        // first request using admin principal

        DomainGroupMembership domainGroupMembership = zms.getPendingDomainGroupMembersList(mockDomRsrcCtx, "user.user1");

        assertNotNull(domainGroupMembership);
        assertNotNull(domainGroupMembership.getDomainGroupMembersList());
        assertEquals(domainGroupMembership.getDomainGroupMembersList().size(), 1);
        for (DomainGroupMembers drm : domainGroupMembership.getDomainGroupMembersList()) {
            assertEquals(drm.getDomainName(), domainName);
            assertNotNull(drm.getMembers());
            for (DomainGroupMember mem : drm.getMembers()) {
                assertNotNull(mem);
                assertEquals(mem.getMemberName(), "user.bob");
                for (GroupMember mr : mem.getMemberGroups()) {
                    assertNotNull(mr);
                    assertEquals(mr.getGroupName(), groupName);
                }
            }
        }

        // first try to delete the pending request without proper authorization

        unsignedCreds = "v=U1;d=user;n=jane";
        rsrcPrince = SimplePrincipal.create("user", "jane", unsignedCreds + ";s=signature",0, principalAuthority);
        assertNotNull(rsrcPrince);
        ((SimplePrincipal) rsrcPrince).setUnsignedCreds(unsignedCreds);
        ResourceContext ctxJane = createResourceContext(rsrcPrince);

        try {
            zms.deletePendingGroupMembership(ctxJane, domainName, groupName, "user.bob", auditRef);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 403);
        }

        // repeat the request using joe principal

        zms.deletePendingGroupMembership(ctxJoe, domainName, groupName, "user.bob", auditRef);

        // check the list to see there are no pending requests

        domainGroupMembership = zms.getPendingDomainGroupMembersList(mockDomRsrcCtx, "user.user1");
        assertNotNull(domainGroupMembership);
        assertTrue(domainGroupMembership.getDomainGroupMembersList().isEmpty());

        zms.deleteTopLevelDomain(mockDomRsrcCtx, domainName, auditRef);
    }

    @Test
    public void testGetPendingDomainGroupMembersList() {

        final String domainName = "pend-dom-grp-mbr-list";
        final String groupName = "group1";

        TopLevelDomain dom1 = createTopLevelDomainObject(domainName, "Approval Test Domain1", "testOrg", "user.user1");
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        setupPrincipalAuditedRoleApprovalByOrg(zms, "user.fury", "testorg");

        DomainMeta meta = createDomainMetaObject("Domain Meta for approval test", "testorg",
                true, true, "12345", 1001);
        zms.putDomainMeta(mockDomRsrcCtx, domainName, auditRef, meta);
        zms.putDomainSystemMeta(mockDomRsrcCtx, domainName, "auditenabled", auditRef, meta);
        setupPrincipalSystemMetaDelete(zms, mockDomRsrcCtx.principal().getFullName(), domainName, "org");
        zms.putDomainSystemMeta(mockDomRsrcCtx, domainName, "org", auditRef, meta);

        Group auditedGroup = createGroupObject(domainName, groupName, "user.john", "user.jane");
        zms.putGroup(mockDomRsrcCtx, domainName, groupName, auditRef, auditedGroup);
        GroupSystemMeta rsm = createGroupSystemMetaObject(true);
        zms.putGroupSystemMeta(mockDomRsrcCtx, domainName, groupName, "auditenabled", auditRef, rsm);
        GroupMeta rm = new GroupMeta().setSelfServe(true);
        zms.putGroupMeta(mockDomRsrcCtx, domainName, groupName, auditRef, rm);

        GroupMembership mbr = new GroupMembership();
        mbr.setMemberName("user.bob");
        mbr.setActive(false);
        mbr.setApproved(false);
        zms.putGroupMembership(mockDomRsrcCtx, domainName, groupName, "user.bob", auditRef, mbr);

        mbr = new GroupMembership();
        mbr.setMemberName("user.bob");
        mbr.setActive(true);
        mbr.setApproved(true);

        try {
            zms.putGroupMembershipDecision(mockDomRsrcCtx, domainName, groupName, "user.bob", auditRef, mbr);
            fail();
        } catch (ResourceException r) {
            assertEquals(r.code, 403);
        }

        // first request using specific principal

        DomainGroupMembership domainGroupMembership = zms.getPendingDomainGroupMembersList(mockDomRsrcCtx, "user.fury");

        assertNotNull(domainGroupMembership);
        assertNotNull(domainGroupMembership.getDomainGroupMembersList());
        assertEquals(domainGroupMembership.getDomainGroupMembersList().size(), 1);
        for (DomainGroupMembers drm : domainGroupMembership.getDomainGroupMembersList()) {
            assertEquals(drm.getDomainName(), domainName);
            assertNotNull(drm.getMembers());
            for (DomainGroupMember mem : drm.getMembers()) {
                assertNotNull(mem);
                assertEquals(mem.getMemberName(), "user.bob");
                for (GroupMember mr : mem.getMemberGroups()) {
                    assertNotNull(mr);
                    assertEquals(mr.getGroupName(), groupName);
                }
            }
        }

        // repeat the request using context principal

        Principal mockPrincipal = Mockito.mock(Principal.class);
        Mockito.when(mockDomRsrcCtx.principal()).thenReturn(mockPrincipal);
        Mockito.when(mockPrincipal.getDomain()).thenReturn("user");
        Mockito.when(mockPrincipal.getFullName()).thenReturn("user.fury");
        domainGroupMembership = zms.getPendingDomainGroupMembersList(mockDomRsrcCtx, null);

        assertNotNull(domainGroupMembership);
        assertNotNull(domainGroupMembership.getDomainGroupMembersList());
        assertEquals(domainGroupMembership.getDomainGroupMembersList().size(), 1);
        for (DomainGroupMembers drm : domainGroupMembership.getDomainGroupMembersList()) {
            assertEquals(drm.getDomainName(), domainName);
            assertNotNull(drm.getMembers());
            for (DomainGroupMember mem : drm.getMembers()) {
                assertNotNull(mem);
                assertEquals(mem.getMemberName(), "user.bob");
                for (GroupMember mr : mem.getMemberGroups()) {
                    assertNotNull(mr);
                    assertEquals(mr.getGroupName(), groupName);
                }
            }
        }

        cleanupPrincipalSystemMetaDelete(zms);
        clenaupPrincipalAuditedRoleApprovalByOrg(zms, "testOrg");

        zms.deleteTopLevelDomain(mockDomRsrcCtx, domainName, auditRef);
    }

    @Test
    public void testPutGroupReview() {

        final String domainName = "group-review-dom";
        final String groupName = "group1";

        TopLevelDomain dom1 = createTopLevelDomainObject(domainName, "Role review Test Domain1", "testOrg", "user.user1");
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        Group group = createGroupObject(domainName, groupName, "user.john", "user.jane");
        zms.putGroup(mockDomRsrcCtx, domainName, groupName, auditRef, group);

        Group inputGroup = new Group().setName(groupName);
        List<GroupMember> inputMembers = new ArrayList<>();
        inputGroup.setGroupMembers(inputMembers);
        inputMembers.add(new GroupMember().setMemberName("user.john").setActive(false));
        inputMembers.add(new GroupMember().setMemberName("user.jane").setActive(true));
        zms.putGroupReview(mockDomRsrcCtx, domainName, groupName, auditRef, inputGroup);

        Group resGroup = zms.getGroup(mockDomRsrcCtx, domainName, groupName, false, false);
        assertEquals(resGroup.getGroupMembers().size(), 1);
        assertEquals(resGroup.getGroupMembers().get(0).getMemberName(), "user.jane");

        zms.deleteTopLevelDomain(mockDomRsrcCtx, domainName, auditRef);
    }

    @Test
    public void testPutGroupReviewError() {

        final String domainName = "group-review-dom-err";
        final String groupName = "group1";

        TopLevelDomain dom1 = createTopLevelDomainObject(domainName, "Role review Test Domain1", "testOrg", "user.user1");
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        Group group = createGroupObject(domainName, groupName, "user.john", "user.jane");
        zms.putGroup(mockDomRsrcCtx, domainName, groupName, auditRef, group);

        Group inputGroup = new Group().setName(groupName);
        List<GroupMember> inputMembers = new ArrayList<>();
        inputGroup.setGroupMembers(inputMembers);
        inputMembers.add(new GroupMember().setMemberName("user.john").setActive(false));
        inputMembers.add(new GroupMember().setMemberName("user.joe").setActive(true));
        zms.putGroupReview(mockDomRsrcCtx, domainName, groupName, auditRef, inputGroup);

        try {
            zms.putGroupReview(mockDomRsrcCtx, domainName, groupName, auditRef, inputGroup);
            fail();
        } catch (ResourceException re) {
            assertEquals(re.getCode(), ResourceException.NOT_FOUND);
        }

        inputGroup.setName("group2");
        try {
            zms.putGroupReview(mockDomRsrcCtx, domainName, groupName, auditRef, inputGroup);
            fail();
        } catch (ResourceException re) {
            assertEquals(re.getCode(), ResourceException.BAD_REQUEST);
        }

        inputGroup.setName(groupName);
        try {
            zms.putGroupReview(mockDomRsrcCtx, "invalid-domain", groupName, auditRef, inputGroup);
            fail();
        } catch (ResourceException re) {
            assertEquals(re.getCode(), ResourceException.NOT_FOUND);
        }

        zms.deleteTopLevelDomain(mockDomRsrcCtx, domainName, auditRef);
    }

    @Test
    public void testGetPrincipalGroups() {

        final String domainName1 = "principal-groups-dom1";
        final String groupName1 = "group1";

        TopLevelDomain dom1 = createTopLevelDomainObject(domainName1, "Test Domain1", "testOrg", "user.user1");
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        Group group1 = createGroupObject(domainName1, groupName1, "user.john", "user.joe");
        zms.putGroup(mockDomRsrcCtx, domainName1, groupName1, auditRef, group1);

        final String domainName2 = "principal-groups-dom2";
        final String groupName2 = "group2";

        TopLevelDomain dom2 = createTopLevelDomainObject(domainName2, "Test Domain1", "testOrg", "user.user1");
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom2);

        Group group2 = createGroupObject(domainName2, groupName2, "user.john", "user.user1");
        zms.putGroup(mockDomRsrcCtx, domainName2, groupName2, auditRef, group2);

        DomainGroupMember dgm = zms.getPrincipalGroups(mockDomRsrcCtx, "user.john", domainName1);
        assertNotNull(dgm);
        List<GroupMember> memberGroups = dgm.getMemberGroups();
        assertEquals(memberGroups.size(), 1);
        assertEquals(memberGroups.get(0).getGroupName(), groupName1);
        assertEquals(memberGroups.get(0).getDomainName(), domainName1);

        dgm = zms.getPrincipalGroups(mockDomRsrcCtx, "user.john", null);
        assertNotNull(dgm);
        memberGroups = dgm.getMemberGroups();
        assertEquals(memberGroups.size(), 2);

        dgm = zms.getPrincipalGroups(mockDomRsrcCtx, null, domainName1);
        assertNotNull(dgm);
        assertTrue(dgm.getMemberGroups().isEmpty());

        dgm = zms.getPrincipalGroups(mockDomRsrcCtx, null, domainName2);
        assertNotNull(dgm);
        memberGroups = dgm.getMemberGroups();
        assertEquals(memberGroups.size(), 1);
        assertEquals(memberGroups.get(0).getGroupName(), groupName2);
        assertEquals(memberGroups.get(0).getDomainName(), domainName2);
    }

    @Test
    public void testVerifyAuthorizedServiceGroupPrefixOperation() {

        // our test resource json includes the following
        // group-prefix use case
        //        "coretech.updater": {
        //            "allowedOperations": [
        //               {
        //                 "name":"putgroupmembership",
        //                  "items": {
        //                      "group-prefix" : [
        //                          "reader.org.",
        //                          "writer.domain."
        //                      ]
        //                  }
        //              }
        //            ]

        zms.verifyAuthorizedServiceGroupOperation(null, "putgroupmembership", "group1");

        // Try passing along operationItem key + value to see if verification works

        zms.verifyAuthorizedServiceGroupOperation("coretech.updater", "putgroupmembership", "reader.org.group1");
        zms.verifyAuthorizedServiceGroupOperation("coretech.updater", "putgroupmembership", "writer.domain.group1");

        // try with restricted operation. Currently, putmembership only allow single operation item.
        zms.verifyAuthorizedServiceGroupOperation("coretech.newsvc", "putgroupmembership", "platforms_deployer");
        zms.verifyAuthorizedServiceGroupOperation("coretech.newsvc", "putgroupmembership", "platforms_different_deployer");

        // Third, try with restriction operation, with not-specified operation item.

        try {
            zms.verifyAuthorizedServiceGroupOperation("coretech.updater", "putgroupmembership", "platforms_deployer_new");
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 403);
        }

        try {
            zms.verifyAuthorizedServiceGroupOperation("coretech.updater", "putgroupmembership", "reader.org1.group1");
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 403);
        }

        try {
            zms.verifyAuthorizedServiceGroupOperation("coretech.newsvc", "putgroupmembership", "platforms_deployer_new");
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 403);
        }
    }

    @Test
    public void testPutGroupMembershipDecisionSelfserveGroupApprove() {

        final String domainName = "self-service-group-approve";
        final String groupName = "group1";

        addMemberToSelfServeGroupWithUserIdentity(domainName, groupName);

        Group resGroup = zms.getGroup(mockDomRsrcCtx, domainName, groupName, false, true);
        assertEquals(resGroup.getGroupMembers().size(), 3);
        for (GroupMember rmem : resGroup.getGroupMembers()) {
            if ("user.bob".equals(rmem.getMemberName())) {
                assertFalse(rmem.getApproved());
            }
        }
        GroupMembership mbr = new GroupMembership();
        mbr.setMemberName("user.bob");
        mbr.setActive(true);
        mbr.setApproved(true);
        zms.putGroupMembershipDecision(mockDomRsrcCtx, domainName, groupName, "user.bob", auditRef, mbr);

        resGroup = zms.getGroup(mockDomRsrcCtx, domainName, groupName, false, false);
        assertEquals(resGroup.getGroupMembers().size(), 3);
        for (GroupMember rmem : resGroup.getGroupMembers()) {
            if ("user.bob".equals(rmem.getMemberName())) {
                assertTrue(rmem.getApproved());
            }
        }
        zms.deleteTopLevelDomain(mockDomRsrcCtx, domainName, auditRef);
    }

    @Test
    public void testPutGroupMembershipDecisionSelfserveRoleReject() {

        final String domainName = "self-service-group-reject";
        final String groupName = "group1";

        addMemberToSelfServeGroupWithUserIdentity(domainName, groupName);

        Group resGroup = zms.getGroup(mockDomRsrcCtx, domainName, groupName, false, true);
        assertEquals(resGroup.getGroupMembers().size(), 3);
        for (GroupMember rmem : resGroup.getGroupMembers()) {
            if ("user.bob".equals(rmem.getMemberName())) {
                assertFalse(rmem.getApproved());
            }
        }
        GroupMembership mbr = new GroupMembership();
        mbr.setMemberName("user.bob");
        mbr.setActive(false);
        mbr.setApproved(false);
        zms.putGroupMembershipDecision(mockDomRsrcCtx, domainName, groupName, "user.bob", auditRef, mbr);

        resGroup = zms.getGroup(mockDomRsrcCtx, domainName, groupName, false, false);
        assertEquals(resGroup.getGroupMembers().size(), 2);

        zms.deleteTopLevelDomain(mockDomRsrcCtx, domainName, auditRef);
    }

    @Test
    public void testPutGroupMembershipDecisionReviewEnabledGroupApprove() {

        final String domainName = "review-enabled-domain";
        TopLevelDomain dom1 = createTopLevelDomainObject(domainName, "Approval test Domain1",
                "testOrg", "user.user1");
        dom1.getAdminUsers().add("user.user2");
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        final String groupName = "review-group";
        Group group1 = createGroupObject(domainName, groupName, null, null);
        zms.putGroup(mockDomRsrcCtx, domainName, groupName, auditRef, group1);

        GroupMeta rm = new GroupMeta().setReviewEnabled(true);
        zms.putGroupMeta(mockDomRsrcCtx, domainName, groupName, auditRef, rm);

        // switch to user.user2 principal to add a member to a group

        Authority principalAuthority = new com.yahoo.athenz.common.server.debug.DebugPrincipalAuthority();
        String unsignedCreds = "v=U1;d=user;n=user2";
        final Principal rsrcPrince = SimplePrincipal.create("user", "user2",
                unsignedCreds + ";s=signature", 0, principalAuthority);
        assertNotNull(rsrcPrince);
        ((SimplePrincipal) rsrcPrince).setUnsignedCreds(unsignedCreds);
        Mockito.when(mockDomRestRsrcCtx.principal()).thenReturn(rsrcPrince);
        Mockito.when(mockDomRsrcCtx.principal()).thenReturn(rsrcPrince);

        GroupMembership mbr = new GroupMembership();
        mbr.setMemberName("user.bob");
        mbr.setActive(false);
        mbr.setApproved(false);

        zms.putGroupMembership(mockDomRsrcCtx, domainName, groupName, "user.bob", auditRef, mbr);

        // verify the user is added with pending state

        Group resgroup = zms.getGroup(mockDomRsrcCtx, domainName, groupName, false, true);
        assertEquals(resgroup.getGroupMembers().size(), 1);
        assertEquals(resgroup.getGroupMembers().get(0).getMemberName(), "user.bob");
        assertFalse(resgroup.getGroupMembers().get(0).getApproved());

        // now try as the admin himself to approve this user and it must
        // be rejected since it has to be done by some other admin

        mbr = new GroupMembership();
        mbr.setMemberName("user.bob");
        mbr.setActive(true);
        mbr.setApproved(true);

        try {
            zms.putGroupMembershipDecision(mockDomRsrcCtx, domainName, groupName, "user.bob", auditRef, mbr);
            fail();
        } catch (ResourceException ex) {
            assertTrue(ex.getMessage().contains("cannot approve his/her own request"));
        }

        // revert back to admin principal

        Authority adminPrincipalAuthority = new com.yahoo.athenz.common.server.debug.DebugPrincipalAuthority();
        String adminUnsignedCreds = "v=U1;d=user;n=user1";
        final Principal rsrcAdminPrince = SimplePrincipal.create("user", "user1",
                adminUnsignedCreds + ";s=signature", 0, adminPrincipalAuthority);
        assertNotNull(rsrcAdminPrince);
        ((SimplePrincipal) rsrcAdminPrince).setUnsignedCreds(adminUnsignedCreds);

        Mockito.when(mockDomRestRsrcCtx.principal()).thenReturn(rsrcAdminPrince);
        Mockito.when(mockDomRsrcCtx.principal()).thenReturn(rsrcAdminPrince);

        // approve the message which should be successful

        zms.putGroupMembershipDecision(mockDomRsrcCtx, domainName, groupName, "user.bob", auditRef, mbr);

        // verify the user is now active

        resgroup = zms.getGroup(mockDomRsrcCtx, domainName, groupName, false, true);
        assertEquals(resgroup.getGroupMembers().size(), 1);
        assertEquals(resgroup.getGroupMembers().get(0).getMemberName(), "user.bob");
        assertTrue(resgroup.getGroupMembers().get(0).getApproved());

        // trying to approve the same user should return 404

        try {
            zms.putGroupMembershipDecision(mockDomRsrcCtx, domainName, groupName, "user.bob", auditRef, mbr);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), ResourceException.NOT_FOUND);
        }

        // now try to approve another use which should also return 404

        mbr.setMemberName("user.joe");
        try {
            zms.putGroupMembershipDecision(mockDomRsrcCtx, domainName, groupName, "user.joe", auditRef, mbr);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), ResourceException.NOT_FOUND);
        }

        zms.deleteTopLevelDomain(mockDomRsrcCtx, domainName, auditRef);
    }

    @Test
    public void testPutGroupMeta() {

        final String domainName = "put-group-meta";
        final String groupName = "group1";

        TopLevelDomain dom1 = createTopLevelDomainObject(domainName, "Group Meta Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        Group group1 = createGroupObject(domainName, groupName, "user.john", "user.jane");
        zms.putGroup(mockDomRsrcCtx, domainName, groupName, auditRef, group1);

        Authority savedAuthority = zms.userAuthority;

        Authority authority = Mockito.mock(Authority.class);
        Mockito.when(authority.getDateAttribute("user.john", "elevated-clearance")).thenReturn(new Date());
        Mockito.when(authority.isAttributeSet("user.john", "OnShore-US")).thenReturn(true);
        Mockito.when(authority.getDateAttribute("user.jane", "elevated-clearance")).thenReturn(new Date());
        Mockito.when(authority.isAttributeSet("user.jane", "OnShore-US")).thenReturn(true);
        Set<String> attrs = new HashSet<>();
        attrs.add("OnShore-US");
        attrs.add("elevated-clearance");
        Mockito.when(authority.booleanAttributesSupported()).thenReturn(attrs);
        Mockito.when(authority.dateAttributesSupported()).thenReturn(attrs);
        zms.userAuthority = authority;

        GroupMeta groupMeta = new GroupMeta();
        zms.putGroupMeta(mockDomRsrcCtx, domainName, groupName, auditRef, groupMeta);

        Group resGroup1 = zms.getGroup(mockDomRsrcCtx, domainName, groupName, true, false);
        assertNotNull(resGroup1);
        assertNull(resGroup1.getSelfServe());
        assertNull(resGroup1.getReviewEnabled());
        assertNull(resGroup1.getNotifyRoles());
        assertNull(resGroup1.getUserAuthorityExpiration());
        assertNull(resGroup1.getUserAuthorityFilter());

        groupMeta = new GroupMeta()
                .setSelfServe(true)
                .setNotifyRoles("role1")
                .setReviewEnabled(false)
                .setUserAuthorityExpiration("elevated-clearance")
                .setUserAuthorityFilter("OnShore-US");
        zms.putGroupMeta(mockDomRsrcCtx, domainName, groupName, auditRef, groupMeta);

        resGroup1 = zms.getGroup(mockDomRsrcCtx, domainName, groupName, true, false);
        assertNotNull(resGroup1);
        assertTrue(resGroup1.getSelfServe());
        assertNull(resGroup1.getReviewEnabled());
        assertEquals(resGroup1.getNotifyRoles(), "role1");
        assertEquals(resGroup1.getUserAuthorityExpiration(), "elevated-clearance");
        assertEquals(resGroup1.getUserAuthorityFilter(), "OnShore-US");

        groupMeta = new GroupMeta().setNotifyRoles("role2,role3");
        zms.putGroupMeta(mockDomRsrcCtx, domainName, groupName, auditRef, groupMeta);

        resGroup1 = zms.getGroup(mockDomRsrcCtx, domainName, groupName, true, false);
        assertNotNull(resGroup1);
        assertNull(resGroup1.getSelfServe()); // default value is false is not specified
        assertNull(resGroup1.getReviewEnabled());
        assertEquals(resGroup1.getNotifyRoles(), "role2,role3");
        assertEquals(resGroup1.getUserAuthorityExpiration(), "elevated-clearance");
        assertEquals(resGroup1.getUserAuthorityFilter(), "OnShore-US");

        zms.userAuthority = savedAuthority;
        zms.deleteTopLevelDomain(mockDomRsrcCtx, domainName, auditRef);
    }

    @Test
    public void testPutGroupMetaMissingAuditRef() {

        final String domainName = "put-group-meta-missing-auditref";
        final String groupName = "group1";

        TopLevelDomain dom1 = createTopLevelDomainObject(domainName, "Group Meta Test Domain1", "testOrg", adminUser);
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        DomainMeta meta = createDomainMetaObject("Domain Meta for Group Meta test", "NewOrg",
                true, true, "12345", 1001);
        zms.putDomainMeta(mockDomRsrcCtx, domainName, auditRef, meta);
        zms.putDomainSystemMeta(mockDomRsrcCtx, domainName, "auditenabled", auditRef, meta);

        Group group1 = createGroupObject(domainName, groupName, "user.john", "user.jane");
        zms.putGroup(mockDomRsrcCtx, domainName, groupName, auditRef, group1);

        GroupSystemMeta rsm = createGroupSystemMetaObject(true);
        zms.putGroupSystemMeta(mockDomRsrcCtx, domainName, groupName, "auditenabled", auditRef, rsm);

        GroupMeta rm = new GroupMeta().setSelfServe(true);
        try {
            zms.putGroupMeta(mockDomRsrcCtx, domainName, groupName, null, rm);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), 400);
            assertTrue(ex.getMessage().contains("Audit reference required"));
        }

        zms.deleteTopLevelDomain(mockDomRsrcCtx, domainName, auditRef);
    }

    @Test
    public void testPutGroupMetaThrowException() {

        final String domainName = "put-group-meta-exc";
        final String groupName = "group1";

        TestAuditLogger alogger = new TestAuditLogger();
        ZMSImpl zmsImpl = getZmsImpl(alogger);
        GroupMeta rm = new GroupMeta();
        rm.setSelfServe(false);

        try {
            zmsImpl.putGroupMeta(mockDomRsrcCtx, domainName, groupName, auditRef, rm);
            fail("notfounderror not thrown.");
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), ResourceException.NOT_FOUND);
        }
    }

    @Test
    public void testPutGroupMetaUserAuthorityFilterSet() {

        final String domainName = "put-group-meta-on-shore";
        final String groupName = "group1";

        TopLevelDomain dom1 = createTopLevelDomainObject(domainName, "Group Meta Test Domain1", "testOrg", "user.user1");
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        Group group1 = createGroupObject(domainName, groupName, "user.john", "user.jane");
        zms.putGroup(mockDomRsrcCtx, domainName, groupName, auditRef, group1);

        Authority savedAuthority = zms.userAuthority;

        Authority authority = Mockito.mock(Authority.class);
        Mockito.when(authority.isAttributeSet("user.john", "OnShore-US")).thenReturn(true);
        Mockito.when(authority.isAttributeSet("user.jane", "OnShore-US")).thenReturn(false);
        Mockito.when(authority.isAttributeSet("user.joe", "OnShore-US")).thenReturn(true);
        Mockito.when(authority.isAttributeSet("user.doe", "OnShore-US")).thenReturn(false);
        Set<String> attrs = new HashSet<>();
        attrs.add("OnShore-US");
        Mockito.when(authority.booleanAttributesSupported()).thenReturn(attrs);
        zms.userAuthority = authority;
        zms.dbService.zmsConfig.setUserAuthority(authority);

        GroupMeta groupMeta = new GroupMeta().setUserAuthorityFilter("OnShore-US");
        zms.putGroupMeta(mockDomRsrcCtx, domainName, groupName, auditRef, groupMeta);

        // john should be active but jane should be disabled

        GroupMembership groupMembership = zms.getGroupMembership(mockDomRsrcCtx, domainName, groupName, "user.john", null);
        assertNotNull(groupMembership);
        assertTrue(groupMembership.getIsMember());
        assertNull(groupMembership.getSystemDisabled());

        groupMembership = zms.getGroupMembership(mockDomRsrcCtx, domainName, groupName, "user.jane", null);
        assertNotNull(groupMembership);
        assertTrue(groupMembership.getIsMember());
        assertEquals(groupMembership.getSystemDisabled().intValue(), 1);

        // we should be able to add joe but not doe

        groupMembership = new GroupMembership().setGroupName(groupName).setMemberName("user.joe");
        zms.putGroupMembership(mockDomRsrcCtx, domainName, groupName, "user.joe", auditRef, groupMembership);

        groupMembership = new GroupMembership().setGroupName(groupName).setMemberName("user.doe");
        try {
            zms.putGroupMembership(mockDomRsrcCtx, domainName, groupName, "user.doe", auditRef, groupMembership);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), ResourceException.BAD_REQUEST);
        }

        // now let's remove our flag

        groupMeta = new GroupMeta().setUserAuthorityFilter("");
        zms.putGroupMeta(mockDomRsrcCtx, domainName, groupName, auditRef, groupMeta);

        // jane should be back to 0 for system disabled flag

        groupMembership = zms.getGroupMembership(mockDomRsrcCtx, domainName, groupName, "user.jane", null);
        assertNotNull(groupMembership);
        assertTrue(groupMembership.getIsMember());
        assertNull(groupMembership.getSystemDisabled());

        // and we're able to add doe now

        groupMembership = new GroupMembership().setGroupName(groupName).setMemberName("user.doe");
        zms.putGroupMembership(mockDomRsrcCtx, domainName, groupName, "user.doe", auditRef, groupMembership);

        zms.dbService.zmsConfig.setUserAuthority(savedAuthority);
        zms.userAuthority = savedAuthority;
        zms.deleteTopLevelDomain(mockDomRsrcCtx, domainName, auditRef);
    }

    @Test
    public void testPutGroupMetaUserAuthorityFilterExpirtySet() {

        final String domainName = "put-group-meta-elevated-clearance";
        final String groupName = "group1";

        TopLevelDomain dom1 = createTopLevelDomainObject(domainName, "Group Meta Test Domain1", "testOrg", "user.user1");
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        Group group1 = createGroupObject(domainName, groupName, "user.john", "user.jane");
        zms.putGroup(mockDomRsrcCtx, domainName, groupName, auditRef, group1);

        Authority savedAuthority = zms.userAuthority;

        Timestamp timestmp = Timestamp.fromMillis(System.currentTimeMillis() + 100000);
        Date date = timestmp.toDate();
        Authority authority = Mockito.mock(Authority.class);
        Mockito.when(authority.getDateAttribute("user.john", "elevated-clearance")).thenReturn(date);
        Mockito.when(authority.getDateAttribute("user.jane", "elevated-clearance")).thenReturn(null);
        Mockito.when(authority.getDateAttribute("user.joe", "elevated-clearance")).thenReturn(date);
        Mockito.when(authority.getDateAttribute("user.doe", "elevated-clearance")).thenReturn(null);
        Set<String> attrs = new HashSet<>();
        attrs.add("elevated-clearance");
        Mockito.when(authority.dateAttributesSupported()).thenReturn(attrs);
        zms.userAuthority = authority;
        zms.dbService.zmsConfig.setUserAuthority(authority);

        GroupMeta groupMeta = new GroupMeta().setUserAuthorityExpiration("elevated-clearance");
        zms.putGroupMeta(mockDomRsrcCtx, domainName, groupName, auditRef, groupMeta);

        // john should be active but jane should be expired

        GroupMembership groupMembership = zms.getGroupMembership(mockDomRsrcCtx, domainName, groupName, "user.john", null);
        assertNotNull(groupMembership);
        assertTrue(groupMembership.getIsMember());
        assertEquals(groupMembership.getExpiration(), timestmp);

        groupMembership = zms.getGroupMembership(mockDomRsrcCtx, domainName, groupName, "user.jane", null);
        assertNotNull(groupMembership);
        assertFalse(groupMembership.getIsMember());
        assertTrue(groupMembership.getExpiration().millis() <= System.currentTimeMillis());

        // we should be able to add joe but not doe

        groupMembership = new GroupMembership().setGroupName(groupName).setMemberName("user.joe");
        zms.putGroupMembership(mockDomRsrcCtx, domainName, groupName, "user.joe", auditRef, groupMembership);

        groupMembership = new GroupMembership().setGroupName(groupName).setMemberName("user.doe");
        try {
            zms.putGroupMembership(mockDomRsrcCtx, domainName, groupName, "user.doe", auditRef, groupMembership);
            fail();
        } catch (ResourceException ex) {
            assertEquals(ex.getCode(), ResourceException.BAD_REQUEST);
        }

        // now let's remove our flag

        groupMeta = new GroupMeta().setUserAuthorityExpiration("");
        zms.putGroupMeta(mockDomRsrcCtx, domainName, groupName, auditRef, groupMeta);

        // jane should be still be inactive since we don't remove
        // expiration flags from users

        groupMembership = zms.getGroupMembership(mockDomRsrcCtx, domainName, groupName, "user.jane", null);
        assertNotNull(groupMembership);
        assertFalse(groupMembership.getIsMember());
        assertTrue(groupMembership.getExpiration().millis() <= System.currentTimeMillis());

        // and we're able to add doe now

        groupMembership = new GroupMembership().setGroupName(groupName).setMemberName("user.doe");
        zms.putGroupMembership(mockDomRsrcCtx, domainName, groupName, "user.doe", auditRef, groupMembership);

        zms.dbService.zmsConfig.setUserAuthority(savedAuthority);
        zms.userAuthority = savedAuthority;
        zms.deleteTopLevelDomain(mockDomRsrcCtx, domainName, auditRef);
    }

    @Test
    public void testPutGroupMembershipDBFailure() {

        final String domainName = "create-group-mbr-db-failure";
        final String groupName = "group1";

        TopLevelDomain dom1 = createTopLevelDomainObject(domainName, "Test Domain1", "testOrg", "user.user1");
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        Group group1 = createGroupObject(domainName, groupName, "user.joe", "user.jane");
        zms.putGroup(mockDomRsrcCtx, domainName, groupName, auditRef, group1);

        // put the db in read-only mode

        setDatabaseReadOnlyMode(true);

        // add a new member

        try {
            GroupMembership mbr = generateGroupMembership(groupName, "user.doe");
            zms.putGroupMembership(mockDomRsrcCtx, domainName, groupName, "user.doe", auditRef, mbr);
            fail();
        } catch (ResourceException ex)  {
            assertEquals(ex.getCode(), ResourceException.GONE);
        }

        // delete an existing member

        try {
            zms.deleteGroupMembership(mockDomRsrcCtx, domainName, groupName, "user.joe", auditRef);
            fail();
        } catch (ResourceException ex)  {
            assertEquals(ex.getCode(), ResourceException.GONE);
        }

        // remove read-only mode

        setDatabaseReadOnlyMode(false);

        zms.deleteTopLevelDomain(mockDomRsrcCtx, domainName, auditRef);
    }

    @Test
    public void testDeleteGroupDBFailure() {

        final String domainName = "del-group-db-failure";
        final String groupName = "group1";

        TopLevelDomain dom1 = createTopLevelDomainObject(domainName, "Test Domain1", "testOrg", "user.user1");
        zms.postTopLevelDomain(mockDomRsrcCtx, auditRef, dom1);

        Group group1 = createGroupObject(domainName, groupName, "user.joe", "user.jane");
        zms.putGroup(mockDomRsrcCtx, domainName, groupName, auditRef, group1);

        // put the db in read-only mode

        setDatabaseReadOnlyMode(true);

        // add a new member

        try {
            zms.deleteGroup(mockDomRsrcCtx, domainName, groupName, auditRef);
            fail();
        } catch (ResourceException ex)  {
            assertEquals(ex.getCode(), ResourceException.GONE);
        }

        // remove read-only mode

        setDatabaseReadOnlyMode(false);

        zms.deleteTopLevelDomain(mockDomRsrcCtx, domainName, auditRef);
    }
}
