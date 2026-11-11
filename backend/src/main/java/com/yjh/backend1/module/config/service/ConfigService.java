package com.yjh.backend1.module.config.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yjh.backend1.common.BizException;
import com.yjh.backend1.common.PasswordRules;
import com.yjh.backend1.module.config.dto.ConfigSaveRequest;
import com.yjh.backend1.module.config.dto.ConfigVO;
import com.yjh.backend1.module.config.entity.SysConfig;
import com.yjh.backend1.module.config.mapper.SysConfigMapper;
import com.yjh.backend1.module.log.service.OperationLogService;
import com.yjh.backend1.security.LoginUser;
import com.yjh.backend1.security.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 系统配置服务（FR-CFG-01/02/03）。
 * <p>
 * 设计要点：
 * <ol>
 *   <li><b>键值对 + 类型标记</b>：值一律按字符串存，{@code configType} 只影响前端控件
 *       与后端校验，不改变存储。这样加一个新配置项不需要改表结构。</li>
 *   <li><b>公开/私有分界在服务层</b>：{@code is_public=1} 的项才能被未登录读。
 *       默认密码这类项如果漏成公开，等于把所有没改过密码的账号钥匙挂在门口，
 *       所以「过滤」写在 SQL 里（eq is_public 1），不依赖调用方记得删字段。</li>
 *   <li><b>缓存 + 主动失效</b>：配置读频率极高（每次密码校验都要读规则），
 *       用本地缓存 + TTL 兜底；任何写操作都调 {@link #evict()}。</li>
 *   <li><b>内置项不可删</b>：{@code builtin=1} 的项可以改值，但不能删 ——
 *       否则代码里 {@code get("site.name", "校园问卷系统")} 会悄悄退化成兜底值，
 *       管理员会以为"我明明设过"。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConfigService {

    private final SysConfigMapper configMapper;
    private final OperationLogService logService;

    /** 允许的配置类型，与前端控件一一对应 */
    private static final Set<String> TYPES = Set.of("STRING", "TEXT", "NUMBER", "BOOLEAN", "PASSWORD");

    /** 允许的分组 */
    private static final Set<String> GROUPS = Set.of("SITE", "SECURITY", "OTHER");

    /** 数值型配置的合理范围，防止管理员把最小长度填成 0 或 999 */
    private static final int MIN_LEN_LOW = 6;
    private static final int MIN_LEN_HIGH = 32;
    private static final int MAX_LEN_LOW = 8;
    private static final int MAX_LEN_HIGH = 64;

    /** key -> 值 的缓存；TTL 用 {} 里说的秒数 */
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    private record CacheEntry(String value, long expireAt) {
        boolean expired() {
            return System.currentTimeMillis() > expireAt;
        }
    }

    /** 缓存 TTL。配置变动很少，给 5 分钟；写操作会主动失效，不靠它保证实时。 */
    private static final long CACHE_TTL_MS = 5 * 60 * 1000L;

    // =========================================================
    // 读取
    // =========================================================

    /** 取字符串配置，不存在或为空时返回默认值。 */
    public String get(String key, String defaultValue) {
        String v = getRaw(key);
        return (v == null || v.isEmpty()) ? defaultValue : v;
    }

    /** 取数值配置，非法时返回默认值。 */
    public int getInt(String key, int defaultValue) {
        String v = getRaw(key);
        if (v == null || v.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            log.warn("配置 {} 的值 '{}' 不是合法数字，使用默认值 {}", key, v, defaultValue);
            return defaultValue;
        }
    }

    /** 取布尔配置，只有 "1"/"true"/"yes" 算真。 */
    public boolean getBool(String key, boolean defaultValue) {
        String v = getRaw(key);
        if (v == null || v.isBlank()) {
            return defaultValue;
        }
        String s = v.trim().toLowerCase();
        return "1".equals(s) || "true".equals(s) || "yes".equals(s);
    }

    private String getRaw(String key) {
        CacheEntry e = cache.get(key);
        if (e != null && !e.expired()) {
            return e.value();
        }
        SysConfig c = configMapper.selectOne(
                Wrappers.<SysConfig>lambdaQuery().eq(SysConfig::getConfigKey, key));
        String v = c == null ? null : c.getConfigValue();
        cache.put(key, new CacheEntry(v, System.currentTimeMillis() + CACHE_TTL_MS));
        return v;
    }

    /**
     * 当前生效的密码规则。
     * <p>
     * 越界值一律 clamp 到合法区间而不是报错：配置被改坏不该导致「谁都不能改密码」，
     * 那等于把管理员自己锁在门外，而且此时他也没法登进来修配置。
     */
    public PasswordRules passwordRules() {
        int min = clamp(getInt("security.password.min-length", 8), MIN_LEN_LOW, MIN_LEN_HIGH);
        int max = clamp(getInt("security.password.max-length", 32), MAX_LEN_LOW, MAX_LEN_HIGH);
        if (max < min) {
            max = min;
        }
        return new PasswordRules(
                min, max,
                getBool("security.password.require-letter", true),
                getBool("security.password.require-digit", true),
                getBool("security.password.require-special", false));
    }

    /** 新建账号 / 重置密码时用的初始密码。 */
    public String defaultPassword() {
        return get("security.default-password", "G14@init2025");
    }

    /**
     * 公开配置：未登录也能读，用于登录页与页脚渲染（FR-CFG-03）。
     * <p>
     * 过滤条件写在 SQL 里，绝不返回 is_public=0 的项 —— 尤其是
     * {@code security.default-password}。
     */
    public Map<String, String> publicMap() {
        List<SysConfig> list = configMapper.selectList(
                Wrappers.<SysConfig>lambdaQuery()
                        .eq(SysConfig::getIsPublic, 1)
                        .orderByAsc(SysConfig::getGroupName, SysConfig::getConfigKey));
        Map<String, String> m = new LinkedHashMap<>();
        for (SysConfig c : list) {
            m.put(c.getConfigKey(), c.getConfigValue() == null ? "" : c.getConfigValue());
        }
        return m;
    }

    /** 全部配置（管理员视角），按分组归拢，密码类项做掩码。 */
    public Map<String, List<ConfigVO>> listGrouped() {
        List<SysConfig> list = configMapper.selectList(
                Wrappers.<SysConfig>lambdaQuery().orderByAsc(SysConfig::getGroupName, SysConfig::getConfigKey));
        Map<String, List<ConfigVO>> grouped = new LinkedHashMap<>();
        for (SysConfig c : list) {
            grouped.computeIfAbsent(c.getGroupName(), k -> new ArrayList<>()).add(toVO(c));
        }
        return grouped;
    }

    private ConfigVO toVO(SysConfig c) {
        ConfigVO vo = new ConfigVO();
        vo.setId(c.getId());
        vo.setConfigKey(c.getConfigKey());
        vo.setConfigType(c.getConfigType());
        vo.setGroupName(c.getGroupName());
        vo.setDescription(c.getDescription());
        vo.setPublicVisible(c.publicVisible());
        vo.setBuiltin(c.builtinOnly());
        vo.setUpdateTime(c.getUpdateTime());
        // 密码类项不把明文发回前端：管理员只需要知道"设过了"，不需要看到值。
        // 掩码而不是 null，是为了让前端能区分"有值"和"没设过"。
        if ("PASSWORD".equals(c.getConfigType())) {
            String v = c.getConfigValue();
            vo.setConfigValue(v == null || v.isEmpty() ? "" : "******");
            vo.setMasked(true);
        } else {
            vo.setConfigValue(c.getConfigValue());
            vo.setMasked(false);
        }
        return vo;
    }

    // =========================================================
    // 写入
    // =========================================================

    /** 批量保存（管理员一次提交整页配置）。只更新已存在的键，不新增。 */
    @Transactional(rollbackFor = Exception.class)
    public int saveBatch(List<ConfigSaveRequest> items, HttpServletRequest httpReq) {
        if (items == null || items.isEmpty()) {
            throw BizException.badRequest("没有需要保存的配置项");
        }
        int changed = 0;
        List<String> changedKeys = new ArrayList<>();
        for (ConfigSaveRequest r : items) {
            if (r.getConfigKey() == null || r.getConfigKey().isBlank()) {
                continue;
            }
            String key = r.getConfigKey().trim();
            SysConfig exist = configMapper.selectOne(
                    Wrappers.<SysConfig>lambdaQuery().eq(SysConfig::getConfigKey, key));
            if (exist == null) {
                // 批量保存不建新键：新增走 create()，那里有完整的键名/类型校验
                throw BizException.notFound("配置项不存在：" + key);
            }
            String value = r.getConfigValue() == null ? "" : r.getConfigValue();
            // PASSWORD 类型收到掩码原样回传时视为"未修改"，否则一保存就会把密码改成 ******
            if ("PASSWORD".equals(exist.getConfigType()) && "******".equals(value)) {
                continue;
            }
            validateValue(exist.getConfigType(), value);

            // 值没变就不要写库，也不要刷 update_time —— 否则"最后修改时间"失去意义
            if (value.equals(exist.getConfigValue() == null ? "" : exist.getConfigValue())) {
                continue;
            }
            SysConfig upd = new SysConfig();
            upd.setId(exist.getId());
            upd.setConfigValue(value);
            configMapper.updateById(upd);
            changed++;
            changedKeys.add(key + "=" + maskIfPassword(exist.getConfigType(), value));
        }

        // 密码规则变了要立刻生效：这里不做精确判断，直接整体失效，代价只是一次重读
        evict();
        validateRuleConsistency();

        if (changed > 0) {
            LoginUser u = UserContext.get();
            logService.write("系统配置", "修改配置", "sys_config", null,
                    "修改 " + changed + " 项：" + String.join("，", changedKeys), httpReq);
        }
        return changed;
    }

    /** 新增自定义配置项（FR-CFG-03 允许扩展键值对）。 */
    @Transactional(rollbackFor = Exception.class)
    public ConfigVO create(ConfigSaveRequest r, HttpServletRequest httpReq) {
        String key = r.getConfigKey() == null ? "" : r.getConfigKey().trim();
        if (key.isEmpty()) {
            throw BizException.badRequest("配置键不能为空");
        }
        if (!key.matches("^[a-zA-Z][a-zA-Z0-9._-]{1,63}$")) {
            throw BizException.badRequest("配置键只能由字母、数字、点、下划线、短横线组成，且以字母开头");
        }
        String type = r.getConfigType() == null ? "STRING" : r.getConfigType().trim().toUpperCase();
        if (!TYPES.contains(type)) {
            throw BizException.badRequest("不支持的配置类型：" + type);
        }
        String group = r.getGroupName() == null ? "OTHER" : r.getGroupName().trim().toUpperCase();
        if (!GROUPS.contains(group)) {
            throw BizException.badRequest("不支持的配置分组：" + group);
        }

        // 必须带 deleted 一起查：uk_key 只挂在 config_key 上，被删的行仍占着这个键名，
        // 直接 insert 会撞唯一键，而普通 selectOne 又看不见那一行。
        SysConfig exist = configMapper.selectByKeyIncludeDeleted(key);

        String value = r.getConfigValue() == null ? "" : r.getConfigValue();
        validateValue(type, value);

        if (exist != null && Integer.valueOf(0).equals(exist.getDeleted())) {
            throw BizException.conflict("配置键已存在：" + key);
        }
        if (exist != null) {
            // 之前被逻辑删除过：复活这一行并覆盖全部属性。
            // 走自定义 SQL，因为 @TableLogic 会给 updateById 自动拼 deleted=0，删掉的行更新不到。
            configMapper.revive(exist.getId(), value, type, group, r.getDescription(),
                    Boolean.TRUE.equals(r.getPublicVisible()) ? 1 : 0, currentUserId());
            evict();
            logService.write("系统配置", "新增配置", "sys_config", exist.getId(),
                    "复活并覆盖 " + key, httpReq);
            return toVO(configMapper.selectById(exist.getId()));
        }

        SysConfig c = new SysConfig();
        c.setConfigKey(key);
        c.setConfigValue(value);
        c.setConfigType(type);
        c.setGroupName(group);
        c.setDescription(r.getDescription());
        c.setIsPublic(Boolean.TRUE.equals(r.getPublicVisible()) ? 1 : 0);
        c.setBuiltin(0);
        c.setDeleted(0);
        configMapper.insert(c);
        evict();
        logService.write("系统配置", "新增配置", "sys_config", c.getId(),
                key + "=" + maskIfPassword(type, value), httpReq);
        return toVO(c);
    }

    /** 删除自定义配置项。内置项不可删。 */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id, HttpServletRequest httpReq) {
        SysConfig c = configMapper.selectById(id);
        if (c == null) {
            throw BizException.notFound("配置项不存在");
        }
        if (c.builtinOnly()) {
            throw BizException.badRequest("内置配置项不能删除，只能修改它的值");
        }
        configMapper.deleteById(id);
        evict();
        logService.write("系统配置", "删除配置", "sys_config", id, "删除 " + c.getConfigKey(), httpReq);
    }

    /** 失效缓存 */
    public void evict() {
        cache.clear();
    }

    // =========================================================
    // 校验
    // =========================================================

    private void validateValue(String type, String value) {
        if (value == null) {
            return;
        }
        switch (type) {
            case "NUMBER" -> {
                try {
                    Integer.parseInt(value.trim());
                } catch (NumberFormatException e) {
                    throw BizException.badRequest("该项要求是数字，当前值：" + value);
                }
            }
            case "BOOLEAN" -> {
                String s = value.trim().toLowerCase();
                if (!Set.of("0", "1", "true", "false").contains(s)) {
                    throw BizException.badRequest("该项只能是 0/1/true/false，当前值：" + value);
                }
            }
            case "PASSWORD" -> {
                // 只挡明显错误（太短 / 只有空白），完整强度校验交给 PasswordPolicy，
                // 否则管理员连一个"临时弱密码"都设不了，反而逼着大家去改代码。
                if (!value.isEmpty() && value.trim().length() < 6) {
                    throw BizException.badRequest("密码长度至少 6 位");
                }
            }
            default -> {
                // STRING / TEXT 不做限制
            }
        }
    }

    /**
     * 保存后再自检一次规则能否正常解析。
     * <p>
     * 例如管理员把 min-length 设成 20、max-length 设成 10，则下次谁改密码都会被拒。
     * 这里读一次 {@link #passwordRules()}，若 clamp 后仍然自相矛盾就打 warn ——
     * 不抛异常是因为值已经落库了，抛出来反而让已成功的保存显示失败。
     */
    private void validateRuleConsistency() {
        int min = getInt("security.password.min-length", 8);
        int max = getInt("security.password.max-length", 32);
        if (max < min) {
            log.warn("密码策略配置自相矛盾：min-length={} > max-length={}，已按 min 生效，请修正", min, max);
        }
    }

    private static String maskIfPassword(String type, String value) {
        return "PASSWORD".equals(type) ? "******" : value;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static Long currentUserId() {
        LoginUser u = UserContext.get();
        return u == null ? null : u.getUserId();
    }
}
