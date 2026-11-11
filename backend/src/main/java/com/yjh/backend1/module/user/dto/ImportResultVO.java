package com.yjh.backend1.module.user.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 批量导入结果。
 * <p>
 * 为什么不是「要么全成功要么全失败」：一次导入几百个学生，其中三个手机号重复
 * 就整批回滚，管理员只能改完再传一遍，改动量大且容易漏。这里采用
 * <b>逐行独立成败</b>：成功的立即生效，失败的逐行回报原因，管理员只补传失败的行。
 * <p>
 * 因此导入接口<b>不</b>加 {@code @Transactional} —— 加了就变成全有全无。
 */
@Data
public class ImportResultVO {

    /** 总数据行数（不含表头与空行） */
    private Integer total;

    /** 成功导入数 */
    private Integer success;

    /** 失败数 = total - success */
    private Integer failed;

    /** 失败明细，最多回传 {@code MAX_ERRORS} 条，避免几万行全错时响应体爆炸 */
    private List<ImportErrorVO> errors = new ArrayList<>();

    /** 失败数超过回传上限时置 true，提示前端「仅显示前 N 条」 */
    private Boolean errorsTruncated = false;

    public void addError(int row, String username, String reason) {
        if (errors.size() < MAX_ERRORS) {
            errors.add(ImportErrorVO.of(row, username, reason));
        } else {
            errorsTruncated = true;
        }
    }

    /** 失败明细回传上限：够管理员定位问题，又不会把响应撑到几 MB */
    public static final int MAX_ERRORS = 200;
}
