package com.github.guoyyit.l2switchsimulatorjava.tests;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * REST 端到端冒烟：建网、注入、泛洪、统计查询。
 */
@SpringBootTest
@AutoConfigureMockMvc
class RestApiIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void fullWorkflowOverHttp() throws Exception {
        mvc.perform(get("/api/sim/ping")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));

        mvc.perform(post("/api/sim/reset").contentType(MediaType.APPLICATION_JSON)
                .content("{\"seed\": 7}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.seed").value(7));

        mvc.perform(post("/api/sim/switches").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"sw\",\"portCount\":4,\"queueCapacity\":16,\"agingTicks\":50}"))
                .andExpect(status().isOk());

        // 广播帧：00:00:00:00:00:01 -> ff:ff:ff:ff:ff:ff
        mvc.perform(post("/api/sim/inject").contentType(MediaType.APPLICATION_JSON)
                .content("{\"switchName\":\"sw\",\"portIndex\":0,\"frameId\":1,"
                        + "\"src\":\"00:00:00:00:00:01\",\"dst\":\"ff:ff:ff:ff:ff:ff\",\"priority\":2,\"vlan\":0}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/sim/snapshot")).andExpect(status().isOk())
                .andExpect(jsonPath("$.switches[0].ports[1].queueSize").value(1))
                .andExpect(jsonPath("$.switches[0].ports[0].queueSize").value(0))
                .andExpect(jsonPath("$.switches[0].fdb[0].mac").value("00:00:00:00:00:01"));

        mvc.perform(post("/api/sim/drain")).andExpect(status().isOk())
                .andExpect(jsonPath("$.delivered").value(3));

        mvc.perform(get("/api/sim/drops")).andExpect(status().isOk());
    }

    @Test
    void triangleTopologyBlocksOnePortOverHttp() throws Exception {
        mvc.perform(post("/api/sim/reset").contentType(MediaType.APPLICATION_JSON)
                .content("{\"seed\": 1}")).andExpect(status().isOk());
        for (String name : new String[]{"s1", "s2", "s3"}) {
            mvc.perform(post("/api/sim/switches").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"" + name + "\",\"portCount\":3}")).andExpect(status().isOk());
        }
        mvc.perform(post("/api/sim/links").contentType(MediaType.APPLICATION_JSON)
                .content("{\"switchA\":\"s1\",\"portA\":2,\"switchB\":\"s2\",\"portB\":2}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/sim/links").contentType(MediaType.APPLICATION_JSON)
                .content("{\"switchA\":\"s2\",\"portA\":1,\"switchB\":\"s3\",\"portB\":1}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/sim/links").contentType(MediaType.APPLICATION_JSON)
                .content("{\"switchA\":\"s3\",\"portA\":2,\"switchB\":\"s1\",\"portB\":1}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/sim/snapshot")).andExpect(status().isOk())
                .andExpect(jsonPath("$.blockedPorts.length()").value(1));
    }
}
