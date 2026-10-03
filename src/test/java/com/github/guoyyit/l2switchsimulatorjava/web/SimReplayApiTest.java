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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 需求 8：回放相关新接口遵循既有快照与错误处理约定。 */
@SpringBootTest
@AutoConfigureMockMvc
class SimReplayApiTest {

    @Autowired
    private MockMvc mvc;
    private final ObjectMapper json = new ObjectMapper();

    private void scenario() throws Exception {
        mvc.perform(post("/api/sim/reset").param("seed", "42")).andExpect(status().isOk());
        mvc.perform(post("/api/sim/switches").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name":"sw","agingTicks":100,"seed":42}
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
        mvc.perform(post("/api/sim/hosts/h1/inject").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"dst":"ff:ff:ff:ff:ff:ff","vlan":1,"priority":0,"payload":"rp"}
                        """)).andExpect(status().isOk());
        mvc.perform(post("/api/sim/tick").param("n", "2")).andExpect(status().isOk());
    }

    @Test
    void replayEndpointReturnsMatchingSnapshot() throws Exception {
        scenario();
        String liveState = mvc.perform(get("/api/sim/state")).andReturn()
                .getResponse().getContentAsString();
        mvc.perform(post("/api/sim/replay"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matched").value(true))
                .andExpect(jsonPath("$.snapshot.tick").value(2));
        String replayState = mvc.perform(post("/api/sim/replay")).andReturn()
                .getResponse().getContentAsString();
        JsonNode live = json.readTree(liveState);
        JsonNode replay = json.readTree(replayState).get("snapshot");
        assertEquals(live, replay);
    }

    @Test
    void eventsEndpointListsRecordedSequence() throws Exception {
        scenario();
        mvc.perform(get("/api/sim/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("Begin"))
                .andExpect(jsonPath("$[0].seq").value(1));
        mvc.perform(get("/api/sim/events").param("from", "1").param("to", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void replayOutOfRangeReturnsDistinguishableCode() throws Exception {
        scenario();
        mvc.perform(post("/api/sim/replay").param("from", "1").param("to", "999999"))
                .andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.code").value("OUT_OF_RANGE"));
        mvc.perform(post("/api/sim/replay").param("from", "9").param("to", "3"))
                .andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.code").value("INVALID_RANGE"));
    }

    @Test
    void replayWithRecordingDisabledRejected() throws Exception {
        mvc.perform(post("/api/sim/reset").param("seed", "1").param("recordEvents", "false"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/sim/replay"))
                .andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.code").value("RECORDING_DISABLED"));
    }

    @Test
    void resetWithCapacityAndTruncationRejectsReplay() throws Exception {
        mvc.perform(post("/api/sim/reset").param("seed", "1")
                .param("recordEvents", "true").param("eventCapacity", "3"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/sim/switches").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name":"sw","agingTicks":100,"seed":1}
                        """)).andExpect(status().isOk());
        mvc.perform(post("/api/sim/switches/sw/ports").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"hostFacing":true,"config":null}
                        """)).andExpect(status().isOk());
        // 第 4 条命令事件因容量达到上限被确定性丢弃（截断）
        mvc.perform(post("/api/sim/switches/sw/ports").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"hostFacing":true,"config":null}
                        """)).andExpect(status().isOk());
        mvc.perform(post("/api/sim/replay"))
                .andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.code").value("LOG_TRUNCATED"));
    }
}
