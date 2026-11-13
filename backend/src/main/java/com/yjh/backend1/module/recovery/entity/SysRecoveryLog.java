package com.yjh.backend1.module.recovery.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 密保找回尝试记录，对应表 {@code sys_recovery_log}。只增不改。
 * <p>
 * 这条通道没有前置登录态，是最容易被拿来做账号枚举的入口。光靠
 * {@code sys_user_recovery.fail_count} 只能挡住「继续猜」，看不到
 * 「谁在什么时候从哪个 IP 试了哪些账号」。出事要查得动，所以留全量流水。
 * <p>
 * 不继承 BaseEntity：这张表没有 update_by / deleted，删日志这件事本身
 * 就不该存在于代码里（与 sys_login_log 同口径）。
 */
@Data
@TableName("sys_recovery_log")
public class SysRecoveryLog implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 命中的用户 ID；账号不存在时为空 */
    private Long userId;
    /** 请求里的账号原文（哪怕它不存在也要记，这才是枚举痕迹） */
    private String username;
    /** LOOKUP / VERIFY / RESET */
    private String step;
    /** 1=成功 0=失败 */
    private Integer result;
    private String reason;
    private String ip;
    private LocalDateTime createTime;
}
