package com.yjh.backend1.module.user.dto;

import lombok.Data;

/**
 * 批量导入时单行的失败原因。
 * <p>
 * 为什么带 {@code row}：管理员拿到「3 条失败」这种结论没有意义，
 * 必须能定位到 Excel 里第几行改什么。行号口径与 Excel 左侧显示一致（表头算第 1 行，
 * 所以第一条数据是第 2 行），不做「从 0 开始」的换算，否则用户对着表格数不上。
 */
@Data
public class ImportErrorVO {

    /** Excel 行号（1 起，含表头，与 Excel 界面左侧数字一致） */
    private Integer row;

    /** 该行的学号/工号；连这一列都是空的时候为 null */
    private String username;

    /** 失败原因，直接展示给管理员 */
    private String reason;

    public static ImportErrorVO of(int row, String username, String reason) {
        ImportErrorVO e = new ImportErrorVO();
        e.setRow(row);
        e.setUsername(username);
        e.setReason(reason);
        return e;
    }
}
