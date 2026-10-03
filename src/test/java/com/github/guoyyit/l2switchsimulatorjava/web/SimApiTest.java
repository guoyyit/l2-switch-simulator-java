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
import static org.junit.jupiter.api.Assertions.assertTrue;
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

    @Test
    void replayEndpointsWorkAndRejectBadRange() throws Exception {
        mvc.perform(post("/api/sim/reset").param("seed", "5")).andExpect(status().isOk());
        mvc.perform(post("/api/sim/switches").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name":"sw","agingTicks":100,"seed":5}
                        """)).andExpect(status().isOk());
        for (int i = 0; i < 2; i++) {
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
        for (int i = 0; i < 5; i++) {
            String body = "{\"dst\":\"ff:ff:ff:ff:ff:ff\",\"vlan\":1,\"priority\":0,\"payload\":\"r" + i + "\"}";
            mvc.perform(post("/api/sim/hosts/h1/inject").contentType(MediaType.APPLICATION_JSON)
                    .content(body)).andExpect(status().isOk());
        }
        mvc.perform(post("/api/sim/tick").param("n", "3")).andExpect(status().isOk());

        // 事件日志状态
        String logStatus = mvc.perform(get("/api/sim/event-log")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode logNode = json.readTree(logStatus);
        assertEquals(true, logNode.get("enabled").asBoolean());
        assertTrue(logNode.get("latestSeq").asLong() >= 0);

        // 回放全部
        String replay = mvc.perform(post("/api/sim/replay")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode replayNode = json.readTree(replay);
        assertEquals(3, replayNode.get("state").get("tick").asInt());
        assertTrue(replayNode.get("events").size() > 0);
        assertTrue(replayNode.get("replayedEvents").asInt() > 0);

        // 查询事件
        String events = mvc.perform(get("/api/sim/events").param("from", "0").param("to", "10"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(json.readTree(events).size() > 0);

        // 配置事件日志开关与容量
        String cfg = mvc.perform(post("/api/sim/event-log").param("enabled", "false").param("capacity", "100"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertEquals(false, json.readTree(cfg).get("enabled").asBoolean());
        // 关闭记录后转发行为不变：再推进仍正常
        mvc.perform(post("/api/sim/tick").param("n", "1")).andExpect(status().isOk());

        // 范围非法：to 超出已记录范围 -> 4xx
        mvc.perform(post("/api/sim/replay").param("from", "0").param("to", "999999"))
                .andExpect(status().is4xxClientError());
        // 范围非法：from < 0 -> 4xx
        mvc.perform(post("/api/sim/replay").param("from", "-1").param("to", "10"))
                .andExpect(status().is4xxClientError());
    }
}
