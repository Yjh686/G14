package com.yjh.backend1.module.user.service;

import com.alibaba.excel.EasyExcel;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yjh.backend1.common.BizException;
import com.yjh.backend1.common.PasswordPolicy;
import com.yjh.backend1.module.config.service.ConfigService;
import com.yjh.backend1.module.log.service.OperationLogService;
import com.yjh.backend1.module.rbac.entity.SysRole;
import com.yjh.backend1.module.rbac.entity.SysUserRole;
import com.yjh.backend1.module.rbac.mapper.SysRoleMapper;
import com.yjh.backend1.module.rbac.mapper.SysUserRoleMapper;
import com.yjh.backend1.module.user.dto.ImportResultVO;
import com.yjh.backend1.module.user.entity.SysUser;
import com.yjh.backend1.module.user.mapper.SysUserMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 用户 Excel 批量导入（FR-USER-06）。
 * <p>
 * 三条设计决定：
 * <ol>
 *   <li><b>逐行独立成败，不整批回滚。</b>一次导三百个学生，如果因为三个手机号重复
 *       就整批失败，管理员得改完再传一遍。这里成功的立即生效、失败的逐行回报行号，
 *       管理员只需要补传失败的那几行。为此本方法<b>没有</b>方法级 {@code @Transactional}，
 *       而是用 {@link TransactionTemplate} 给每一行开一个小事务 —— 保证「用户 + 角色」
 *       要么都进要么都不进，又不会让单行失败牵连整批。</li>
 *   <li><b>不允许通过导入创建管理员。</b>批量造管理员是灾难的起点，且这类操作应该是
 *       深思熟虑的一次一次点，而不是「复制粘贴一百行」。角色列只接受学生 / 教师。</li>
 *   <li><b>初始密码可留空。</b>留空则用系统默认密码并置 {@code must_change_password=1}，
 *       首次登录强制改密 —— 批量导入的密码必然会被传阅，必须一次性。</li>
 * </ol>
 * 逐行校验而非先全量校验再插入，是因为「库内唯一性」在插入过程中自己会变
 * （第 5 行插进去的学号，第 9 行再出现就该报重复），全量预校验做不到这一点。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserImportService {

    private final SysUserMapper userMapper;
    private final SysRoleMapper roleMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final BCryptPasswordEncoder passwordEncoder;
    private final OperationLogService operationLogService;
    private final TransactionTemplate transactionTemplate;

    /** 默认密码与密码规则来自系统配置（FR-CFG-02） */
    private final ConfigService configService;

    /** 单次导入上限：够一个学院用，又不至于让一次请求跑几分钟 */
    public static final int MAX_ROWS = 1000;

    private static final Pattern USERNAME = Pattern.compile("^[A-Za-z0-9_.-]{4,64}$");
    private static final Pattern PHONE = Pattern.compile("^1[3-9]\\d{9}$");

    /** 表头文案（顺序即列序），导入校验与模板下载共用同一份定义 */
    private static final List<String> HEADERS = List.of(
            "学号/工号*", "姓名*", "角色*", "院系", "手机号", "邮箱", "初始密码");

    // =========================================================
    // 导入
    // =========================================================

    public ImportResultVO importUsers(MultipartFile file, HttpServletRequest httpReq) {
        if (file == null || file.isEmpty()) {
            throw BizException.badRequest("请选择要导入的 Excel 文件");
        }
        String name = file.getOriginalFilename();
        if (name == null || !(name.toLowerCase().endsWith(".xlsx") || name.toLowerCase().endsWith(".xls"))) {
            throw BizException.badRequest("只支持 .xlsx 或 .xls 格式");
        }

        List<Map<Integer, String>> rows = readRows(file);
        if (rows.isEmpty()) {
            throw BizException.badRequest("文件是空的，请按模板填写后重新导入");
        }

        checkHeader(rows.get(0));

        // 预先把库内已有的学号与手机号取到内存：
        // 逐行查库的话 1000 行就是 2000 次查询，而全校账号数也就几百，一次取回更快。
        //
        // 注意这里必须过滤 null：只 select 一列时，若该列在某一行为 NULL，
        // MyBatis 认为整行没有任何数据，会把该行映射成 null 元素而不是一个空实体。
        // 手机号本来就可空，不过滤会直接在下面的循环里 NPE。
        Set<String> usedUsernames = new HashSet<>();
        Set<String> usedPhones = new HashSet<>();
        for (SysUser u : userMapper.selectList(Wrappers.<SysUser>lambdaQuery()
                .select(SysUser::getUsername, SysUser::getPhone))) {
            if (u == null) {
                continue;
            }
            if (u.getUsername() != null) {
                usedUsernames.add(u.getUsername());
            }
            if (u.getPhone() != null && !u.getPhone().isBlank()) {
                usedPhones.add(u.getPhone());
            }
        }

        // 角色一次查出来，避免每行都查一次字典
        Map<String, SysRole> roles = new HashMap<>();
        for (SysRole r : roleMapper.selectList(Wrappers.<SysRole>lambdaQuery())) {
            roles.put(r.getCode(), r);
        }

        ImportResultVO result = new ImportResultVO();
        int dataRows = 0;

        for (int i = 1; i < rows.size(); i++) {
            Map<Integer, String> row = rows.get(i);
            int excelRow = i + 1;                      // Excel 左侧行号：表头是第 1 行

            if (isBlankRow(row)) {
                continue;                              // 尾部空行不算数据，也不报错
            }
            dataRows++;
            if (dataRows > MAX_ROWS) {
                throw BizException.badRequest("单次最多导入 " + MAX_ROWS + " 个账号，请拆分后分多次导入");
            }

            String username = cell(row, 0);
            try {
                String realName = cell(row, 1);
                String roleText = cell(row, 2);
                String department = cell(row, 3);
                String phone = cell(row, 4);
                String email = cell(row, 5);
                String password = cell(row, 6);

                validateRow(username, realName, phone, password);

                SysRole role = resolveRole(roleText, roles);
                if (usedUsernames.contains(username)) {
                    throw BizException.conflict("学号/工号已存在");
                }
                if (!phone.isEmpty() && usedPhones.contains(phone)) {
                    throw BizException.conflict("手机号已被其他账号使用");
                }

                boolean useDefault = password.isEmpty();
                String rawPassword = useDefault ? configService.defaultPassword() : password;

                // 单行一个事务：用户与角色绑定要么都成功，要么都不留下
                transactionTemplate.executeWithoutResult(status -> {
                    SysUser u = new SysUser();
                    u.setUsername(username);
                    u.setPasswordHash(passwordEncoder.encode(rawPassword));
                    u.setRealName(realName);
                    u.setStatus(1);
                    u.setAuditStatus(1);                       // ADMIN 导入 = 已审核
                    u.setMustChangePassword(useDefault ? 1 : 0);
                    u.setDepartment(blankToNull(department));
                    u.setPhone(blankToNull(phone));
                    u.setEmail(blankToNull(email));
                    u.setRemark("批量导入");
                    u.setTokenVersion(0);
                    u.setLoginFailCount(0);
                    userMapper.insert(u);

                    SysUserRole ur = new SysUserRole();
                    ur.setUserId(u.getId());
                    ur.setRoleId(role.getId());
                    userRoleMapper.insert(ur);
                });

                // 插进去之后立刻占位，保证同一文件里重复的学号能被后面的行检出
                usedUsernames.add(username);
                if (!phone.isEmpty()) {
                    usedPhones.add(phone);
                }
                result.setSuccess(nz(result.getSuccess()) + 1);
            } catch (BizException e) {
                result.addError(excelRow, username, e.getMessage());
            } catch (Exception e) {
                // 单行出错不能拖垮整批：记日志（含堆栈）供排查，对管理员只回一句概括
                log.warn("批量导入第 {} 行失败 username={}", excelRow, username, e);
                result.addError(excelRow, username, "写入失败：" + e.getClass().getSimpleName());
            }
        }

        if (dataRows == 0) {
            throw BizException.badRequest("文件里没有数据行，请按模板填写后重新导入");
        }

        result.setTotal(dataRows);
        result.setFailed(dataRows - nz(result.getSuccess()));

        // 整批只记一条日志：逐行记会在日志表里塞进上千条，反而淹没了别的操作
        operationLogService.write("用户管理", "批量导入", "USER", null,
                "导入 " + dataRows + " 行，成功 " + result.getSuccess() + " 条，失败 " + result.getFailed() + " 条",
                httpReq);

        return result;
    }

    // =========================================================
    // 模板下载
    // =========================================================

    /**
     * 下载导入模板。
     * <p>
     * 有模板和没模板的差别很大：没模板时管理员只能靠文档描述去猜列序，
     * 十有八九第一次都会导错。模板里带一行示例值，顺便把「角色写学生或教师」这种
     * 隐含约定用示例表达出来。
     */
    public void writeTemplate(HttpServletResponse response) throws IOException {
        response.reset();
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding(null);           // 让容器不要动我们的字节
        String encoded = URLEncoder.encode("用户导入模板.xlsx", StandardCharsets.UTF_8).replace("+", "%20");
        response.setHeader("Content-Disposition",
                "attachment; filename=\"template.xlsx\"; filename*=UTF-8''" + encoded);
        response.setHeader("Access-Control-Expose-Headers", "Content-Disposition");

        List<List<Object>> data = new ArrayList<>();
        data.add(List.of("20241001", "张三", "学生", "计算机学院2024级1班", "13800138000", "zhangsan@example.com", ""));
        data.add(List.of("T2024001", "李老师", "教师", "计算机学院", "", "li@example.com", ""));

        EasyExcel.write(response.getOutputStream())
                .head(HEADERS.stream().map(List::of).toList())
                .sheet("用户")
                .doWrite(data);
    }

    // =========================================================
    // 内部
    // =========================================================

    /**
     * 读成 {@code List<Map<列号, 文本>>}，表头也保留（headRowNumber(0)）。
     * <p>
     * 保留表头是为了先校验列对不对 —— 管理员传错文件（比如传了成绩单）时，
     * 直接报「第 1 行应是表头」比逐行报「学号不能为空」有用得多。
     */
    private List<Map<Integer, String>> readRows(MultipartFile file) {
        try (InputStream is = file.getInputStream()) {
            List<Map<Integer, String>> rows = EasyExcel.read(is).sheet(0).headRowNumber(0).doReadSync();
            return rows == null ? List.of() : rows;
        } catch (IOException e) {
            throw BizException.badRequest("读取文件失败：" + e.getMessage());
        } catch (Exception e) {
            // EasyExcel 对非 xlsx 内容会抛各种解析异常，统一转成 400
            throw BizException.badRequest("无法解析该文件，请确认是 Excel 且未加密");
        }
    }

    private void checkHeader(Map<Integer, String> head) {
        if (head == null || head.isEmpty()) {
            throw BizException.badRequest("第 1 行应是表头，请下载模板后按格式填写");
        }
        // 只校验前三列：后四列可以留空，但学号/姓名/角色缺一不可
        String c0 = cell(head, 0);
        String c1 = cell(head, 1);
        String c2 = cell(head, 2);
        if (!c0.contains("学号") && !c0.contains("工号")) {
            throw BizException.badRequest("第 1 列应是「学号/工号」，当前是「" + c0 + "」，请下载模板对照");
        }
        if (!c1.contains("姓名")) {
            throw BizException.badRequest("第 2 列应是「姓名」，当前是「" + c1 + "」，请下载模板对照");
        }
        if (!c2.contains("角色")) {
            throw BizException.badRequest("第 3 列应是「角色」，当前是「" + c2 + "」，请下载模板对照");
        }
    }

    private void validateRow(String username, String realName, String phone, String password) {
        if (username.isEmpty()) {
            throw BizException.badRequest("学号/工号不能为空");
        }
        if (!USERNAME.matcher(username).matches()) {
            throw BizException.badRequest("学号/工号只能包含字母、数字、下划线、点和短横线，长度 4~64");
        }
        if (realName.isEmpty()) {
            throw BizException.badRequest("姓名不能为空");
        }
        if (realName.length() > 50) {
            throw BizException.badRequest("姓名不能超过 50 字");
        }
        if (!phone.isEmpty() && !PHONE.matcher(phone).matches()) {
            throw BizException.badRequest("手机号格式不正确");
        }
        if (!password.isEmpty()) {
            String msg = PasswordPolicy.validate(password, configService.passwordRules());
            if (msg != null) {
                throw BizException.badRequest("初始密码不合规：" + msg);
            }
        }
    }

    /**
     * 角色文案映射。同时接受中文与权限码两套写法，因为不同学院的表格习惯不一样；
     * 但**明确拒绝管理员**。
     */
    private SysRole resolveRole(String text, Map<String, SysRole> roles) {
        String t = text == null ? "" : text.trim();
        if (t.isEmpty()) {
            throw BizException.badRequest("角色不能为空（学生 / 教师）");
        }
        String code;
        if (t.equals("学生") || t.equalsIgnoreCase("STUDENT") || t.equalsIgnoreCase("ROLE_STUDENT")) {
            code = SysRole.STUDENT;
        } else if (t.equals("教师") || t.equals("老师")
                || t.equalsIgnoreCase("TEACHER") || t.equalsIgnoreCase("ROLE_TEACHER")) {
            code = SysRole.TEACHER;
        } else if (t.contains("管理") || t.equalsIgnoreCase("ADMIN") || t.equalsIgnoreCase("ROLE_ADMIN")) {
            throw BizException.badRequest("不支持通过导入创建管理员，请在用户管理页逐个添加");
        } else {
            throw BizException.badRequest("无法识别的角色「" + t + "」，只能填学生或教师");
        }
        SysRole role = roles.get(code);
        if (role == null) {
            throw BizException.badRequest("系统缺少角色 " + code + "，请检查初始化数据");
        }
        return role;
    }

    private boolean isBlankRow(Map<Integer, String> row) {
        if (row == null) {
            return true;
        }
        for (int i = 0; i < HEADERS.size(); i++) {
            if (!cell(row, i).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private static String cell(Map<Integer, String> row, int index) {
        if (row == null) {
            return "";
        }
        String v = row.get(index);
        return v == null ? "" : v.trim();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
