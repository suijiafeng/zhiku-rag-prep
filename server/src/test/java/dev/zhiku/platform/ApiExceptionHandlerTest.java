package dev.zhiku.platform;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** 先查后插的并发重复由数据库唯一约束拦下；接口要把它报告为 409，而不是 500。 */
class ApiExceptionHandlerTest {
  @RestController
  static class Duplicating {
    @PostMapping("/dup")
    void create() {
      throw new DuplicateKeyException("duplicate key value violates unique constraint \"document_title_key\"");
    }
  }

  @Test
  void aUniqueConstraintViolationIsAConflict() throws Exception {
    var mvc = MockMvcBuilders.standaloneSetup(new Duplicating()).setControllerAdvice(new ApiExceptionHandler()).build();
    mvc.perform(post("/dup")).andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("ALREADY_EXISTS"))
        .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("document_title_key"))));
  }
}
