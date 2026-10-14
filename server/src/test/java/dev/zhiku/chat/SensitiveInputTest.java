package dev.zhiku.chat;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SensitiveInputTest {
  @Test
  void findsIdCardBankCardAndPhoneNumbers() {
    assertThat(SensitiveInput.detect("我的身份证号是 11010119900307123X，年假几天")).isTrue();
    assertThat(SensitiveInput.detect("工资卡 6222020200112233445 为什么没到账")).isTrue();
    assertThat(SensitiveInput.detect("请回电 13812345678")).isTrue();
    assertThat(SensitiveInput.detect("年假按司龄计算，10 年以上 15 天")).isFalse();
    assertThat(SensitiveInput.detect("2026 年第 3 季度报销截止 12 月 31 日")).isFalse();
  }

  @Test
  void redactsEveryMatch() {
    assertThat(SensitiveInput.redact("身份证 11010119900307123X，电话 13812345678"))
        .isEqualTo("身份证 [已隐藏]，电话 [已隐藏]");
  }

  @Test
  void pointsToTheHumanChannelOnlyWhenOneIsConfigured() {
    assertThat(ChatService.handoff("")).isEqualTo("人工渠道（尚未配置）");
    assertThat(ChatService.handoff("人事服务台 8001")).isEqualTo("人事服务台 8001");
  }
}
