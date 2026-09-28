package com.github.guoyyit.l2switchsimulatorjava.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** REST API 的端到端切片测试。 */
@SpringBootTest
@AutoConfigureMockMvc
class SimApiTest {

    @Autowired
    private MockMvc mvc;
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void fullScenarioThroughHttp() throws Exception {
        mvc.perform(post("/api/sim/reset").param("seed", "42")).andExpect(status().isOk());

        mvc.perform(post("/api/sim/switches").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name":"sw","agingTicks":100,"seed":42}
                        """)).andExpect(status().isOk());
        for (int i = 0; i < 3; i++) {
            mvc.perform(post("/api/sim/switches/sw/ports").contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"hostFacing":true,"config":null}
                            """)).andExpect(status().isOk());
        }
        mvc.perform(post("/api/sim/hosts").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name":"h1","mac":"00:00:00:00:00:01"}
                        """)).andExpect(status().isOk());
        mvc.perform(post("/api/sim/hosts").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name":"h2","mac":"00:00:00:00:00:02"}
                        """)).andExpect(status().isOk());
        mvc.perform(post("/api/sim/hosts/h1/connect").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"switchName":"sw","portId":0}
                        """)).andExpect(status().isOk());
        mvc.perform(post("/api/sim/hosts/h2/connect").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"switchName":"sw","portId":1}
                        """)).andExpect(status().isOk());

        mvc.perform(post("/api/sim/hosts/h1/inject").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"dst":"ff:ff:ff:ff:ff:ff","vlan":1,"priority":0,"payload":"api-hello"}
                        """)).andExpect(status().isOk());
        mvc.perform(post("/api/sim/tick").param("n", "2")).andExpect(status().isOk());

        String received = mvc.perform(get("/api/sim/hosts/h2/received"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode arr = json.readTree(received);
        assertEquals(1, arr.size());
        assertEquals("api-hello", arr.get(0).get("payload").asText());
        assertEquals("00:00:00:00:00:01", arr.get(0).get("src").asText());

        JsonNode state = json.readTree(mvc.perform(get("/api/sim/state"))
                .andReturn().getResponse().getContentAsString());
        assertEquals(2, state.get("tick").asInt());
        assertEquals(1, state.get("totals").get("hostDelivered").asInt());
        assertEquals(1, state.get("switches").get(0).get("fdb").size());
    }

    @Test
    void badInputReturns4xxNot5xxSilence() throws Exception {
        mvc.perform(post("/api/sim/reset").param("seed", "1")).andExpect(status().isOk());
        // 向不存在的交换机注入拓扑应返回 4xx
        mvc.perform(post("/api/sim/switches/ghost/ports").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"hostFacing":true,"config":null}
                        """)).andExpect(status().is4xxClientError());
    }
}
