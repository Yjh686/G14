package com.g14.g14.login.enums;

/**
 * 角色枚举。
 *
 * <p>数据库以<b>字符串</b>（枚举 name）存储与返回，与前端
 * {@code src/utils/role.js} 的 {@code ROLE} 常量逐字对齐：
 * {@code STUDENT / TEACHER / ADMIN}。前端角色判断与路由守卫依赖该一致性。
 */
public enum RoleEnum {

    /** 学生：学号登录，只能查看 / 填写自己所在班级的问卷。 */
    STUDENT("学生"),

    /** 老师：工号或手机号登录，只能管理自己创建的班级与问卷。 */
    TEACHER("老师"),

    /** 管理员：拥有全部权限。 */
    ADMIN("管理员");

    private final String label;

    RoleEnum(String label) {
        this.label = label;
    }

    /** 角色中文名，供界面直接展示。 */
    public String getLabel() {
        return label;
    }

    /**
     * 安全解析：非法值返回 null，由调用方决定如何处理
     * （避免 IllegalArgumentException 泄漏到接口层）。
     */
    public static RoleEnum parse(String name) {
        if (name == null) {
            return null;
        }
        for (RoleEnum r : values()) {
            if (r.name().equalsIgnoreCase(name.trim())) {
                return r;
            }
        }
        return null;
    }
}
