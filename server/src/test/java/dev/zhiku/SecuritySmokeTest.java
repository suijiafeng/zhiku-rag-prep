package dev.zhiku;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@SpringBootTest @AutoConfigureMockMvc
class SecuritySmokeTest {
 @Autowired MockMvc mvc;
 @Test void publicStatusHasNoModel() throws Exception { mvc.perform(get("/api/status")).andExpect(status().isOk()).andExpect(jsonPath("$.modelEnabled").value(false)); }
 @Test void businessReadDenied() throws Exception { mvc.perform(get("/api/knowledge-bases")).andExpect(status().isForbidden()); }
 @Test void businessWriteDenied() throws Exception { mvc.perform(post("/api/chat").contentType("application/json").content("{}" )).andExpect(status().isForbidden()); }
}
