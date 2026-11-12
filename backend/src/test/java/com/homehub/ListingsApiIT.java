package com.homehub;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import io.restassured.RestAssured;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Functional test: the full app on the 'local' profile against the running
 * emulator (needs `source scripts/emulator-env.sh`; `make test-functional`
 * does that). Bootstrap + seeding run at startup, so assertions use the
 * fixture ids from test/data/listings.json.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ListingsApiIT {

    @LocalServerPort
    int port;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
    }

    private static List<String> ids(String query) {
        return given().get("/listings/search?" + query)
                .then().statusCode(200)
                .extract().jsonPath().getList("items.listingId");
    }

    @Test
    @DisplayName("R-SRCH-01.3/.5 Seattle: only ACTIVE + UNDER_OFFER, price ascending; DRAFT/SOLD absent")
    void searchSeattle() {
        assertThat(ids("city=Seattle")).containsExactly("LST-0002", "LST-0001", "LST-0003");
    }

    @Test
    @DisplayName("R-SRCH-01 city match is case-insensitive")
    void searchCaseInsensitive() {
        assertThat(ids("city=sEaTtLe")).containsExactly("LST-0002", "LST-0001", "LST-0003");
    }

    @Test
    @DisplayName("R-SRCH-01.2 price range via the GSI key condition")
    void searchPriceRange() {
        assertThat(ids("city=Seattle&minPrice=600000&maxPrice=900000")).containsExactly("LST-0002", "LST-0001");
        assertThat(ids("city=Seattle&minPrice=1000000")).containsExactly("LST-0003");
        assertThat(ids("city=Portland&maxPrice=400000")).containsExactly("LST-0007");
    }

    @Test
    void searchMinBeds() {
        assertThat(ids("city=Seattle&minBeds=3")).containsExactly("LST-0001", "LST-0003");
    }

    @Test
    void searchUnknownCityIsEmpty() {
        assertThat(ids("city=Nowhere")).isEmpty();
    }

    @Test
    @DisplayName("R-SRCH-01.4 pagination walks every result exactly once")
    void searchPagination() {
        var first = given().get("/listings/search?city=Seattle&limit=2").then().statusCode(200).extract().jsonPath();
        assertThat(first.getList("items.listingId")).containsExactly("LST-0002", "LST-0001");
        String token = first.getString("nextToken");
        assertThat(token).isNotBlank();

        var second = given().queryParam("city", "Seattle").queryParam("limit", 2).queryParam("nextToken", token)
                .get("/listings/search").then().statusCode(200).extract().jsonPath();
        assertThat(second.getList("items.listingId")).containsExactly("LST-0003");
        assertThat(second.getString("nextToken")).isNull();
    }

    @Test
    @DisplayName("R-SRCH-01.1 missing city is 400 problem+json")
    void searchWithoutCity() {
        given().get("/listings/search")
                .then().statusCode(400)
                .contentType("application/problem+json")
                .body("errors[0].field", equalTo("city"));
    }

    @Test
    void searchWithForgedTokenIs400() {
        given().get("/listings/search?city=Seattle&nextToken=garbage")
                .then().statusCode(400).body("errors[0].field", equalTo("nextToken"));
    }

    @Test
    @DisplayName("R-LIST-02 get fixture listing with ETag")
    void getListing() {
        given().get("/listings/LST-0008")
                .then().statusCode(200)
                .header("ETag", "\"1\"")
                .body("city", equalTo("Austin"))
                .body("price", equalTo(560000))
                .body("baths", equalTo(2.5f));
    }

    @Test
    void getSoldListingIsVisible() {
        given().get("/listings/LST-0005").then().statusCode(200).body("status", equalTo("SOLD"));
    }

    @Test
    @DisplayName("R-LIST-02 DRAFT and unknown listings are 404")
    void getDraftOrUnknown() {
        given().get("/listings/LST-0004").then().statusCode(404).body("title", equalTo("Listing not found"));
        given().get("/listings/LST-9999").then().statusCode(404);
    }

    @Test
    void readinessIsUpAfterBootstrapAndSeed() {
        given().get("/actuator/health/readiness").then().statusCode(200).body("status", equalTo("UP"));
    }
}
