package org.vaadin.filebrowser.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.vaadin.filebrowser.data.Access;
import org.vaadin.filebrowser.data.FsRoot;
import org.vaadin.filebrowser.data.RootStatus;
import org.vaadin.filebrowser.data.TestRoots;
import org.vaadin.filebrowser.service.FileSystemRegistry;
import org.vaadin.filebrowser.service.StorageService;
import org.vaadin.filebrowser.storage.LocalDiskProvider;

/**
 * File URLs are plain Spring MVC endpoints next to the Vaadin app, so these run requests through the app's real
 * security filter chain to show they need the same OIDC sign-in as everything else. Storage is mocked, so a refused
 * request is also shown never to reach it.
 */
@SpringBootTest(properties = {
        "spring.security.oauth2.client.registration.test.client-id=test",
        "spring.security.oauth2.client.registration.test.client-secret=test",
        "spring.security.oauth2.client.registration.test.scope=openid,email",
        "spring.security.oauth2.client.registration.test.authorization-grant-type=authorization_code",
        "spring.security.oauth2.client.registration.test.redirect-uri={baseUrl}/login/oauth2/code/{registrationId}",
        "spring.security.oauth2.client.provider.test.authorization-uri=http://localhost:9/authorize",
        "spring.security.oauth2.client.provider.test.token-uri=http://localhost:9/token",
        "spring.security.oauth2.client.provider.test.jwk-set-uri=http://localhost:9/jwks",
        "spring.security.oauth2.client.provider.test.user-name-attribute=sub",
})
class FileStreamSecurityTest {

    private static final String FILE = "/files/r1/notes.txt";

    @TempDir
    static Path data;

    @TempDir
    Path files;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("filebrowser.database-path", () -> data.resolve("test.db").toString());
    }

    @MockitoBean
    FileSystemRegistry registry;

    @MockitoBean
    StorageService storage;

    @Autowired
    WebApplicationContext context;

    private MockMvc mvc;

    @BeforeEach
    void setUp() throws IOException {
        Files.writeString(files.resolve("notes.txt"), "notes");
        final var root = new FsRoot("r1", "Disk", "disk", "/srv", Map.of(), Access.READ_WRITE, TestRoots.ALL_ALLOWED,
                RootStatus.UNKNOWN);
        when(registry.find("r1")).thenReturn(Optional.of(root));
        final var provider = new LocalDiskProvider(files, name -> "generic", type -> "disk");
        when(storage.provider(any())).thenReturn(provider);
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @Test
    void servesTheFileToASignedInUser() throws Exception {
        mvc.perform(get(FILE).with(oidcLogin()))
                .andExpect(status().isOk())
                .andExpect(content().string("notes"));
    }

    @Test
    void refusesARequestWithoutSignIn() throws Exception {
        mvc.perform(get(FILE))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(""));
        verifyNoInteractions(registry, storage);
    }

    @Test
    void refusesHeadRangeAndPageRequestsWithoutSignIn() throws Exception {
        mvc.perform(head(FILE)).andExpect(status().isUnauthorized());
        mvc.perform(get(FILE).header(HttpHeaders.ACCEPT, "text/html").header("Sec-Fetch-Dest", "document"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(FILE).header(HttpHeaders.RANGE, "bytes=0-1")).andExpect(status().isUnauthorized());
        verifyNoInteractions(registry, storage);
    }

    @Test
    void refusesAnAnonymousAuthentication() throws Exception {
        mvc.perform(get(FILE).with(anonymous())).andExpect(status().isUnauthorized());
        verifyNoInteractions(registry, storage);
    }

    /** Paths that try to reach the controller through a public path; the firewall refuses them before any rule. */
    @ParameterizedTest
    @ValueSource(strings = {
            "/oidc/../files/r1/notes.txt",
            "/oidc/..%2Ffiles/r1/notes.txt",
            "/oidc/%2e%2e/files/r1/notes.txt",
            "/styles.css/../files/r1/notes.txt",
            "/files/r1/notes.txt;jsessionid=x",
            "/files;x=y/r1/notes.txt",
            "/files//r1/notes.txt",
            "/files/r1/%2e%2e/r1/notes.txt",
            "/files%2Fr1%2Fnotes.txt",
    })
    void refusesPathTricksBeforeAnyRule(String path) throws Exception {
        mvc.perform(get(URI.create(path))).andExpect(status().isBadRequest());
        verifyNoInteractions(registry, storage);
    }

    /** Forms of a file URL that pass the firewall, so only the sign-in rule stops them. */
    @ParameterizedTest
    @ValueSource(strings = {
            "/files",
            "/files/r1",
            "/files/r1/",
            "/files/r1/notes.txt/",
            "/files/r1/notes.txt?download=1",
            "/files/unknown/notes.txt",
    })
    void refusesEveryFormOfAFileUrlWithoutSignIn(String path) throws Exception {
        mvc.perform(get(URI.create(path))).andExpect(status().isUnauthorized());
        verifyNoInteractions(registry, storage);
    }

    @Test
    void letsOnlyTheAppFrameFiles() throws Exception {
        mvc.perform(get(FILE).with(oidcLogin()))
                .andExpect(header().string("X-Frame-Options", "SAMEORIGIN"));
        mvc.perform(get("/starred").with(oidcLogin()))
                .andExpect(header().string("X-Frame-Options", "DENY"));
        mvc.perform(get("/starred"))
                .andExpect(header().string("X-Frame-Options", "DENY"));
    }

    @Test
    void otherPagesStillSendYouToSignIn() throws Exception {
        mvc.perform(get("/starred").header(HttpHeaders.ACCEPT, "text/html").header("Sec-Fetch-Dest", "document"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/oauth2/authorization/test"));
    }
}
