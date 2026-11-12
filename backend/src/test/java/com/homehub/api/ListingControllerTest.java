package com.homehub.api;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.homehub.TestListings;
import com.homehub.domain.InvalidRequestException;
import com.homehub.domain.ListingNotFoundException;
import com.homehub.domain.ListingStatus;
import com.homehub.domain.Page;
import com.homehub.service.ListingService;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Web slice: HTTP mapping, JSON shape, problem+json, CORS. Service is mocked. */
@WebMvcTest(ListingController.class)
@ActiveProfiles("test")
@TestPropertySource(properties = "homehub.cors.allowed-origins=http://localhost:5173")
class ListingControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    ListingService service;

    @Test
    @DisplayName("R-SRCH-01 returns items and nextToken")
    void search() throws Exception {
        when(service.search("Seattle", 1L, 2L, 3, 5, "t0"))
                .thenReturn(new Page<>(List.of(TestListings.listing("LST-0001", ListingStatus.ACTIVE)), "t1"));

        mvc.perform(get("/listings/search?city=Seattle&minPrice=1&maxPrice=2&minBeds=3&limit=5&nextToken=t0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].listingId").value("LST-0001"))
                .andExpect(jsonPath("$.items[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.items[0].price").value(850000))
                .andExpect(jsonPath("$.items[0].createdAt").value("2026-10-01T10:00:00Z"))
                .andExpect(jsonPath("$.nextToken").value("t1"));
    }

    @Test
    void searchLastPageOmitsNextToken() throws Exception {
        when(service.search(eq("Seattle"), any(), any(), any(), any(), any())).thenReturn(new Page<>(List.of(), null));

        mvc.perform(get("/listings/search?city=Seattle"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)))
                .andExpect(jsonPath("$.nextToken").doesNotExist());
    }

    @Test
    @DisplayName("R-LIST-01.1 validation errors are problem+json with field errors")
    void searchInvalid() throws Exception {
        when(service.search(any(), any(), any(), any(), any(), any()))
                .thenThrow(InvalidRequestException.of("city", "is required"));

        mvc.perform(get("/listings/search"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Content-Type", "application/problem+json"))
                .andExpect(jsonPath("$.title").value("Invalid request"))
                .andExpect(jsonPath("$.errors[0].field").value("city"))
                .andExpect(jsonPath("$.errors[0].message").value("is required"));
    }

    @Test
    void nonNumericParamIsBadRequest() throws Exception {
        mvc.perform(get("/listings/search?city=x&minPrice=lots"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Content-Type", "application/problem+json"));
    }

    @Test
    @DisplayName("R-LIST-02 returns listing with ETag = version")
    void getById() throws Exception {
        when(service.get("LST-0001")).thenReturn(TestListings.listing("LST-0001", ListingStatus.ACTIVE));

        mvc.perform(get("/listings/LST-0001"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.listingId").value("LST-0001"))
                .andExpect(jsonPath("$.baths").value(2.5));
    }

    @Test
    void getUnknownIs404Problem() throws Exception {
        when(service.get("LST-9999")).thenThrow(new ListingNotFoundException("LST-9999"));

        mvc.perform(get("/listings/LST-9999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Listing not found"))
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void corsAllowsConfiguredUiOrigin() throws Exception {
        mvc.perform(options("/listings/search")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
    }

    @Test
    void corsRejectsOtherOrigins() throws Exception {
        mvc.perform(options("/listings/search")
                        .header("Origin", "http://evil.example")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden());
    }
}
