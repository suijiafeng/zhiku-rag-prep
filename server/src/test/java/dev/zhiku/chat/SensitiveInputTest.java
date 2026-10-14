package dev.zhiku.chat;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 号码均为虚构：证件号与银行卡号按校验位构造。 */
class SensitiveInputTest {
  @Test
  void findsIdCardBankCardAndPhoneNumbers() {
    assertThat(SensitiveInput.detect("我的身份证号是 110101199003071233，年假几天")).contains("证件号");
    assertThat(SensitiveInput.detect("工资卡 6222020200112233446 为什么没到账")).contains("银行卡号");
    assertThat(SensitiveInput.detect("请回电 13812345678")).contains("手机号");
    assertThat(SensitiveInput.detect("年假按司龄计算，10 年以上 15 天")).isEmpty();
    assertThat(SensitiveInput.detect("2026 年第 3 季度报销截止 12 月 31 日")).isEmpty();
  }

  @Test
  void longNumbersWithoutAValidChecksumAreNotPersonalNumbers() {
    assertThat(SensitiveInput.detect("订单 1234567890123456 什么时候报销")).isEmpty();
    assertThat(SensitiveInput.detect("合同编号 20260101000000000001 的付款条件")).isEmpty();
    assertThat(SensitiveInput.detect("工号 11010119900307123X 属于哪个部门")).isEmpty();
    assertThat(SensitiveInput.redact("订单 1234567890123456")).isEqualTo("订单 1234567890123456");
  }

  @Test
  void redactsEveryVerifiedMatch() {
    assertThat(SensitiveInput.redact("身份证 110101199003071233，电话 13812345678，单号 1234567890123456"))
        .isEqualTo("身份证 [已隐藏]，电话 [已隐藏]，单号 1234567890123456");
  }

  @Test
  void pointsToTheHumanChannelOnlyWhenOneIsConfigured() {
    assertThat(ChatService.handoff("")).isEqualTo("人工渠道（尚未配置）");
    assertThat(ChatService.handoff("人事服务台 8001")).isEqualTo("人事服务台 8001");
  }
}
