package com.qn.calendar.web;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class SpaResourceConfigurationTests {

    private static final String INDEX_MARKER = "spa-resource-test-index";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void servesRootThroughNoStoreIndexPage() throws Exception {
        String forwardedIndex = mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("index.html"))
                .andReturn()
                .getResponse()
                .getForwardedUrl();

        mockMvc.perform(get("/" + forwardedIndex))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(content().string(containsString(INDEX_MARKER)));
    }

    @Test
    void disablesCachingForIndexPage() throws Exception {
        mockMvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(content().string(containsString(INDEX_MARKER)));
    }

    @Test
    void disablesCachingForSpaFallback() throws Exception {
        mockMvc.perform(get("/completed-stats"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(content().string(containsString(INDEX_MARKER)));
    }

    @Test
    void cachesHashedAssetsAsImmutableForOneYear() throws Exception {
        mockMvc.perform(get("/assets/index-testhash.js"))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        HttpHeaders.CACHE_CONTROL,
                        allOf(
                                containsString("public"),
                                containsString("max-age=31536000"),
                                containsString("immutable")
                        )
                ))
                .andExpect(content().string(containsString("spaResourceAsset")));
    }

    @Test
    void disablesCachingForFavicon() throws Exception {
        mockMvc.perform(get("/favicon.ico"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
    }

    @Test
    void doesNotServeIndexPageForMissingApiResource() throws Exception {
        mockMvc.perform(get("/api/missing"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(not(containsString(INDEX_MARKER))));
    }

}
