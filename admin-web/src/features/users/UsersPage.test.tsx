import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import { RoleMatrix, UsersPage, isUuid } from "./UsersPage";
import type { RolePolicy } from "../../api/client";

const policy: RolePolicy = {
  roles: ["IT_ADMIN", "KB_OPERATOR", "EMPLOYEE"],
  actions: ["ASK_AND_CITE", "MANAGE_DOCUMENTS", "DEBUG_AND_EVALUATE", "READ_OTHERS_CONVERSATIONS"],
  rules: {
    IT_ADMIN: { ASK_AND_CITE: "ALLOWED", MANAGE_DOCUMENTS: "REQUIRES_EXPLICIT_GRANT", DEBUG_AND_EVALUATE: "REQUIRES_EXPLICIT_GRANT", READ_OTHERS_CONVERSATIONS: "NEVER" },
    KB_OPERATOR: { ASK_AND_CITE: "ALLOWED", MANAGE_DOCUMENTS: "ALLOWED", DEBUG_AND_EVALUATE: "ALLOWED", READ_OTHERS_CONVERSATIONS: "REQUIRES_EXPLICIT_GRANT" },
    EMPLOYEE: { ASK_AND_CITE: "ALLOWED", MANAGE_DOCUMENTS: "NEVER", DEBUG_AND_EVALUATE: "NEVER", READ_OTHERS_CONVERSATIONS: "NEVER" },
  },
};

describe("users and permissions", () => {
  it("renders the role matrix from the server policy", () => {
    const html = renderToStaticMarkup(<RoleMatrix policy={policy} />);
    expect(html).toContain("IT 管理员");
    expect(html).toContain("知识库运营");
    expect(html).toContain("普通员工");
    expect(html.match(/✓/g)).toHaveLength(5);
    expect(html.match(/需显式授权/g)).toHaveLength(3);
  });

  it("does not invent a user directory when disconnected", () => {
    const html = renderToStaticMarkup(<UsersPage client={undefined} />);
    expect(html).toContain("身份提供方");
    expect(html).not.toContain("@corp.com");
    expect(html).not.toContain("邀请用户");
  });

  it("accepts only UUID user ids", () => {
    expect(isUuid("00000000-0000-4000-8000-0000000000a1")).toBe(true);
    expect(isUuid("zhangwei@corp.com")).toBe(false);
    expect(isUuid("")).toBe(false);
  });
});
