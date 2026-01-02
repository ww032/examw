package com.yf.system.modules.user.service.impl;

import com.baidu.aip.face.AipFace;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.json.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.yf.ability.Constant;
import com.yf.ability.captcha.service.CaptchaService;
import com.yf.ability.redis.service.RedisService;
import com.yf.ability.shiro.dto.SysUserLoginDTO;
import com.yf.ability.shiro.jwt.JwtUtils;
import com.yf.ability.shiro.service.ShiroUserService;
import com.yf.base.api.api.ApiError;
import com.yf.base.api.api.dto.PagingReqDTO;
import com.yf.base.api.exception.ServiceException;
import com.yf.base.utils.BeanMapper;
import com.yf.base.utils.CacheKey;
import com.yf.base.utils.jackson.JsonHelper;
import com.yf.base.utils.passwd.PassHandler;
import com.yf.base.utils.passwd.PassInfo;
import com.yf.system.modules.config.enums.FuncSwitch;
import com.yf.system.modules.config.service.CfgSwitchService;
import com.yf.system.modules.menu.service.SysMenuService;
import com.yf.system.modules.role.entity.SysRole;
import com.yf.system.modules.user.UserUtils;
import com.yf.system.modules.user.dto.request.*;
import com.yf.system.modules.user.dto.response.UserListRespDTO;
import com.yf.system.modules.user.entity.SysUser;
import com.yf.system.modules.user.enums.SysRoleId;
import com.yf.system.modules.user.enums.SysUserId;
import com.yf.system.modules.user.enums.UserState;
import com.yf.system.modules.user.mapper.SysUserMapper;
import com.yf.system.modules.user.service.SysUserRoleService;
import com.yf.system.modules.user.service.SysUserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang3.StringUtils;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.util.*;
import java.util.function.Function;

/**
 * <p>
 * 语言设置 服务实现类
 * </p>
 *
 * @author 聪明笨狗
 * @since 2020-04-13 16:57
 */
@Log4j2
@Service
@RequiredArgsConstructor
public class SysUserServiceImpl extends ServiceImpl<SysUserMapper, SysUser> implements SysUserService, ShiroUserService {

    private final SysUserRoleService sysUserRoleService;
    private final RedisService redisService;
    private final CaptchaService captchaService;
    private final CfgSwitchService cfgSwitchService;
    private final SysMenuService sysMenuService;
    private final AipFace aipFace; // 新增：注入百度AI人脸客户端

    @Override
    public SysUserSaveReqDTO detail(String id) {
        // 基础信息复制
        SysUser user = this.getById(id);
        SysUserSaveReqDTO respDTO = new SysUserSaveReqDTO();
        BeanMapper.copy(user, respDTO);

        // 角色是要
        List<SysRole> roleList = sysUserRoleService.listRoles(user.getId());
        List<String> roles = new ArrayList<>();
        for (SysRole role : roleList) {
            roles.add(role.getId());
        }
        respDTO.setRoles(roles);

        // 清理掉密码
        respDTO.setPassword(null);
        respDTO.setSalt(null);

        return respDTO;
    }

    @Override
    public IPage<UserListRespDTO> paging(PagingReqDTO<SysUserQueryReqDTO> reqDTO) {
        return baseMapper.paging(reqDTO.toPage(), reqDTO.getParams());
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public void delete(List<String> ids) {
        // 超级用户可以删除任何用户
        if(!SysUserId.ADMIN.equals(UserUtils.getUserId())){
            int count = sysUserRoleService.countWithLevel(ids, UserUtils.getRoleLevel());
            if (count < ids.size()) {
                throw new ServiceException("删除错误，可能存在越权操作！");
            }
        }

        if (ids.contains(UserUtils.getUserId())) {
            throw new ServiceException("您不可以删除自己的账号！");
        }

        // 移除数据
        this.removeByIds(ids);
    }

    @Override
    public SysUserLoginDTO login(SysUserLoginReqDTO reqDTO) {
        // 校验图形验证码
        if (!StringUtils.isBlank(reqDTO.getCaptchaKey())) {
            boolean check = captchaService.checkCaptcha(reqDTO.getCaptchaKey(), reqDTO.getCaptchaValue());
            if (!check) {
                throw new ServiceException("图形验证码不正确或已失效！");
            }
        }

        QueryWrapper<SysUser> wrapper = new QueryWrapper<>();
        wrapper.lambda().eq(SysUser::getUserName, reqDTO.getUserName());
        SysUser user = this.getOne(wrapper, false);

        // 校验用户状态&密码
        return this.checkAndLogin(user, reqDTO.getPassword());
    }

    @Override
    public SysUserLoginDTO faceLogin(SysUserFaceLoginReqDTO reqDTO) {
        // 步骤1：可选：校验图形验证码（与原有/login接口保持一致）
        if (!StringUtils.isBlank(reqDTO.getCaptchaKey())) {
            boolean check = captchaService.checkCaptcha(reqDTO.getCaptchaKey(), reqDTO.getCaptchaValue());
            if (!check) {
                throw new ServiceException("图形验证码不正确或已失效！");
            }
        }

        // 步骤2：根据用户名查询用户（复用原有/login接口查询逻辑）
        QueryWrapper<SysUser> wrapper = new QueryWrapper<>();
        wrapper.lambda().eq(SysUser::getUserName, reqDTO.getUserName());
        SysUser user = this.getOne(wrapper, false);

        // 步骤3：复用原有密码&用户状态校验逻辑
        this.preCheckUserAndPassword(user, reqDTO.getPassword());

        // 步骤4：校验身份证号（确保SysUser实体有idCard字段）
        if (user == null || StringUtils.isBlank(user.getIdCard())) {
            throw new ServiceException("该账号未绑定身份证，无法进行人脸登录！");
        }
        if (!reqDTO.getIdCard().equalsIgnoreCase(user.getIdCard())) {
            throw new ServiceException(ApiError.ERROR_90010002);
        }

        // 步骤5：低版本SDK核心：直接使用前端传递的纯Base64字符串（无需解码）
        String faceImageBase64 = reqDTO.getFaceImageBase64();
        if (StringUtils.isBlank(faceImageBase64)) {
            throw new ServiceException("人脸照片不能为空！");
        }

        // 步骤6：关键修改：将Map改为HashMap（严格匹配低版本SDK方法参数要求）
        // 声明为HashMap<String, String>，而非Map<String, String>
        HashMap<String, String> options = new HashMap<>();
        options.put("quality_control", "NORMAL"); // 图片质量控制：正常级别
        options.put("liveness_control", "LOW"); // 活体检测：低要求（免费版支持）

        // 步骤7：调用低版本SDK支持的personVerify方法（参数类型完全匹配，无报错）
        // 五个参数严格对应：身份证号、姓名(null)、人脸Base64、图片类型(BASE64)、HashMap配置
        JSONObject faceResult = aipFace.personVerify(
                reqDTO.getIdCard(),
                null,
                faceImageBase64,
                "BASE64",
                options // 此处传入HashMap，与方法参数类型完全匹配
        );

        // 步骤8：解析百度AI返回结果（后续逻辑不变，无修改）
        if (faceResult == null) {
            log.error("百度AI接口调用无响应，用户名：{}", reqDTO.getUserName());
            throw new ServiceException("人脸验证接口调用失败，请稍后再试！");
        }
        Integer errorCodeObj = (Integer) faceResult.get("error_code");
        int errorCode = errorCodeObj == null ? -1 : errorCodeObj;
        String errorMsg = faceResult.getString("error_msg");
        if (errorCode != 0) {
            log.error("百度AI人脸识别失败，错误码：{}，错误信息：{}，用户名：{}", errorCode, errorMsg, reqDTO.getUserName());
            throw new ServiceException("人脸识别失败：" + errorMsg);
        }

        // 步骤9：校验人脸比对置信度（后续逻辑不变，无修改）
        JSONObject resultObj = faceResult.getJSONObject("result");
        if (resultObj == null) {
            throw new ServiceException("人脸验证结果解析失败，请重新上传人脸照片！");
        }
        Double confidence = resultObj.getDouble("confidence");
        if (confidence == null || confidence < 80.0) {
            throw new ServiceException("人脸与身份证信息不匹配，验证失败！");
        }

        // 步骤10：复用原有逻辑生成Token并保存会话
        log.info("人脸验证成功，用户名：{}", reqDTO.getUserName());
        return this.setToken(user);
    }

    /**
     * 辅助方法：复用原有密码&用户状态校验逻辑
     * @param user 待校验用户
     * @param password 登录密码
     */
    private void preCheckUserAndPassword(SysUser user, String password) {
        if (user == null) {
            throw new ServiceException(ApiError.ERROR_90010001);
        }

        // 被禁用
        if (UserState.DISABLED.equals(user.getState())) {
            throw new ServiceException(ApiError.ERROR_90010005);
        }

        // 待审核
        if (UserState.AUDIT.equals(user.getState())) {
            throw new ServiceException(ApiError.ERROR_90010006);
        }

        if (!StringUtils.isBlank(password)) {
            boolean pass = PassHandler.checkPass(password, user.getSalt(), user.getPassword());
            if (!pass) {
                throw new ServiceException(ApiError.ERROR_90010002);
            }
        }
    }

    /**
     * 用户登录校验
     *
     * @param user
     */
    private SysUserLoginDTO checkAndLogin(SysUser user, String password) {
        if (user == null) {
            throw new ServiceException(ApiError.ERROR_90010001);
        }

        // 被禁用
        if (UserState.DISABLED.equals(user.getState())) {
            throw new ServiceException(ApiError.ERROR_90010005);
        }

        // 待审核
        if (UserState.AUDIT.equals(user.getState())) {
            throw new ServiceException(ApiError.ERROR_90010006);
        }

        if (!StringUtils.isBlank(password)) {
            boolean pass = PassHandler.checkPass(password, user.getSalt(), user.getPassword());
            if (!pass) {
                throw new ServiceException(ApiError.ERROR_90010002);
            }
        }

        return this.setToken(user);
    }

    @Override
    public List<String> permissions(String userId) {
        return sysUserRoleService.findUserPermission(userId);
    }

    @Override
    public List<String> roles(String userId) {
        return sysUserRoleService.listRoleIds(userId);
    }

    @Override
    public SysUserLoginDTO token(String token) {
        // 获得会话
        String username;
        try {
            username = JwtUtils.getUsername(token);
        } catch (Exception e) {
            throw new ServiceException("会话失效，请重新登录！");
        }

        log.error("++++++++用户名：{}", username);

        Map<String, Object> json = redisService.getJson(Constant.USER_NAME_KEY + username);
        if (json == null) {
            throw new ServiceException(ApiError.ERROR_10010002);
        }

        return JsonHelper.parseObject(json, SysUserLoginDTO.class);
    }

    @CacheEvict(value = CacheKey.TOKEN, key = "#token")
    @Override
    public void logout(String token) {
        // 遵循T下线原则
        boolean tick = cfgSwitchService.isOn(FuncSwitch.LOGIN_TICK);
        if (tick) {
            try {
                String username = JwtUtils.getUsername(token);
                String[] keys = new String[]{Constant.USER_NAME_KEY + username};
                redisService.del(keys);
            } catch (Exception e) {
                log.error(e);
            }
        }
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public void update(SysUserUpdateReqDTO reqDTO) {
        // 更新用户资料
        SysUser user = this.getById(UserUtils.getUserId());
        BeanMapper.copy(reqDTO, user);

        // 修改标识
        boolean reLogin = false;

        // 修改密码
        String password = reqDTO.getPassword();
        if (!StringUtils.isBlank(password)) {
            PassInfo passInfo = PassHandler.buildPassword(password);
            user.setPassword(passInfo.getPassword());
            user.setSalt(passInfo.getSalt());
            reLogin = true;
        }

        // 重新登录
        if (reLogin) {
            // 退出登录
            String[] keys = new String[]{Constant.USER_NAME_KEY + user.getUserName()};
            redisService.del(keys);
        }

        // 更新信息
        this.updateById(user);
    }

    @Override
    public void pass(SysUserPassReqDTO reqDTO) {
        // 旧密码不能与新密码一致
        boolean same = reqDTO.getOldPass().equals(reqDTO.getNewPass());
        if(same){
            throw new ServiceException("新密码不能与旧密码一样！");
        }

        // 获取当前用户
        QueryWrapper<SysUser> wrapper = new QueryWrapper<>();
        wrapper.lambda()
                .select(SysUser::getId, SysUser::getPassword, SysUser::getSalt)
                .eq(SysUser::getId, UserUtils.getUserId());
        SysUser user = this.getOne(wrapper, false);

        // 旧密码不对
        boolean check = PassHandler.checkPass(reqDTO.getOldPass(), user.getSalt(), user.getPassword());
        if (!check) {
            throw new ServiceException(ApiError.ERROR_90010007);
        }

        PassInfo passInfo = PassHandler.buildPassword(reqDTO.getNewPass());
        user.setPassword(passInfo.getPassword());
        user.setSalt(passInfo.getSalt());
        this.updateById(user);
    }

    @CacheEvict(value = CacheKey.MENU, allEntries = true)
    @Transactional(rollbackFor = Exception.class)
    @Override
    public void save(SysUserSaveReqDTO reqDTO) {
        List<String> roles = reqDTO.getRoles();

        if (CollectionUtils.isEmpty(roles)) {
            throw new ServiceException(ApiError.ERROR_90010003);
        }

        QueryWrapper<SysUser> wrapper = new QueryWrapper<>();
        wrapper.lambda().eq(SysUser::getUserName, reqDTO.getUserName());
        if (!StringUtils.isBlank(reqDTO.getId())) {
            wrapper.lambda().ne(SysUser::getId, reqDTO.getId());
        }

        long count = this.count(wrapper);
        if (count > 0) {
            throw new ServiceException("用户名不能重复！");
        }

        // 保存基本信息
        SysUser user;

        // 添加模式
        if (StringUtils.isBlank(reqDTO.getId())) {
            user = new SysUser();
            BeanMapper.copy(reqDTO, user);
            user.setId(IdWorker.getIdStr());
        } else {
            user = this.getById(reqDTO.getId());
            BeanMapper.copy(reqDTO, user);
        }

        // 级别
        int level = sysUserRoleService.findMaxLevel(reqDTO.getId());
        if (level > UserUtils.getRoleLevel()) {
            throw new ServiceException("越级操作，不能操作等级高的用户！");
        }

        // 修改密码
        if (!StringUtils.isBlank(reqDTO.getPassword())) {
            PassInfo pass = PassHandler.buildPassword(reqDTO.getPassword());
            user.setPassword(pass.getPassword());
            user.setSalt(pass.getSalt());
        }

        // 保存角色信息
        sysUserRoleService.saveRoles(user.getId(), roles, true);

        // 保存绑定关系
        this.saveOrUpdate(user);
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public SysUserLoginDTO reg(UserRegReqDTO reqDTO) {
        boolean check = captchaService.checkCaptcha(reqDTO.getCaptchaKey(), reqDTO.getCaptchaValue());
        if (!check) {
            throw new ServiceException("图形验证码不正确或已失效！");
        }

        // 功能开关
        boolean on = cfgSwitchService.isOn(FuncSwitch.USER_REG);
        if (!on) {
            throw new ServiceException("管理员未开启用户注册！");
        }

        QueryWrapper<SysUser> wrapper = new QueryWrapper<>();
        wrapper.lambda()
                .select(SysUser::getId)
                .eq(SysUser::getUserName, reqDTO.getUserName());

        // 用户名即为手机号
        boolean exists = this.count(wrapper) > 0;
        if (exists) {
            throw new ServiceException("用户名已存在，换一个吧！！");
        }

        return this.saveAndLogin(
                null,
                reqDTO.getUserName(),
                reqDTO.getDeptCode(),
                reqDTO.getRealName(),
                null,
                null,
                "",
                reqDTO.getPassword());
    }

    /**
     * 保存用户并自动登录
     *
     * @param userName
     * @param deptCode
     * @param realName
     * @param mobile
     * @param avatar
     * @param password
     * @return
     */
    private SysUserLoginDTO saveAndLogin(String userId, String userName, String deptCode, String realName, String role, String mobile, String avatar, String password) {
        // 保存用户
        SysUser user = new SysUser();

        // 指定用户ID的
        if (!StringUtils.isBlank(userId)) {
            user.setId(userId);
        } else {
            user.setId(IdWorker.getIdStr());
        }

        // 指定部门
        boolean on = cfgSwitchService.isOn(FuncSwitch.USER_DEPT_TYPE);
        if (on) {
            deptCode = cfgSwitchService.val(FuncSwitch.USER_DEPT_CODE);
        }

        // 需要审核
        boolean audit = cfgSwitchService.isOn(FuncSwitch.USER_AUDIT);
        if (audit) {
            user.setState(UserState.AUDIT);
        } else {
            user.setState(UserState.NORMAL);
        }

        user.setUserName(userName);
        user.setRealName(realName);
        user.setDeptCode(deptCode);
        user.setMobile(mobile);
        user.setAvatar(avatar);
        PassInfo passInfo = PassHandler.buildPassword(password);
        user.setPassword(passInfo.getPassword());
        user.setSalt(passInfo.getSalt());

        // 保存角色
        List<String> roleList = new ArrayList<>();
        if (!StringUtils.isBlank(role)) {
            roleList.add(role);
        } else {
            // 默认用户
            roleList.add(SysRoleId.USER);
        }

        // 保存角色
        sysUserRoleService.saveRoles(user.getId(), roleList, false);

        // 保存用户
        this.save(user);

        return this.setToken(user);
    }

    /**
     * 保存会话信息
     *
     * @param user
     * @return
     */
    private SysUserLoginDTO setToken(SysUser user) {
        // 获取一个用户登录的信息
        String key = Constant.USER_NAME_KEY + user.getUserName();
        String json = redisService.getString(key);
        if (!StringUtils.isBlank(json)) {
            // 删除旧的会话
            redisService.del(key);
        }

        SysUserLoginDTO respDTO = new SysUserLoginDTO();
        BeanMapper.copy(user, respDTO);

        // 正常状态才登录
        if (UserState.NORMAL.equals(user.getState())) {
            // 根据用户生成Token
            String token = JwtUtils.sign(user.getUserName());
            respDTO.setToken(token);

            // 添加角色信息
            this.fillRoleData(respDTO);

            // 权限表，用于前端控制按钮
            List<String> permissions = sysMenuService.listPermissionByRoles(respDTO.getRoles());
            respDTO.setPermissions(permissions);

            // 保存如Redis
            redisService.set(key, JsonHelper.toJson(respDTO));
        }

        return respDTO;
    }

    /**
     * 追加用户角色信息
     *
     * @param respDTO
     */
    private void fillRoleData(SysUserLoginDTO respDTO) {
        // 角色是要
        List<SysRole> roleList = sysUserRoleService.listRoles(respDTO.getId());
        // 角色级别
        Integer roleLevel = 0;
        // 数据权限1最小：查看自己的数据
        Integer dataScope = 1;

        List<String> roleIds = new ArrayList<>();
        for (SysRole role : roleList) {
            // 角色ID
            roleIds.add(role.getId());
            // 替换大的权限
            if (dataScope < role.getDataScope()) {
                dataScope = role.getDataScope();
            }
            // 权限级别
            if (roleLevel < role.getRoleLevel()) {
                roleLevel = role.getRoleLevel();
            }
        }
        respDTO.setRoleLevel(roleLevel);
        respDTO.setDataScope(dataScope);
        respDTO.setRoles(roleIds);
    }

    @Override
    public boolean saveBatch(Collection<SysUser> entityList, int batchSize) {
        return false;
    }

    @Override
    public boolean saveOrUpdateBatch(Collection<SysUser> entityList, int batchSize) {
        return false;
    }

    @Override
    public boolean updateBatchById(Collection<SysUser> entityList, int batchSize) {
        return false;
    }

    @Override
    public boolean saveOrUpdate(SysUser entity) {
        return false;
    }

    @Override
    public SysUser getOne(Wrapper<SysUser> queryWrapper, boolean throwEx) {
        return null;
    }

    @Override
    public Optional<SysUser> getOneOpt(Wrapper<SysUser> queryWrapper, boolean throwEx) {
        return Optional.empty();
    }

    @Override
    public Map<String, Object> getMap(Wrapper<SysUser> queryWrapper) {
        return null;
    }

    @Override
    public <V> V getObj(Wrapper<SysUser> queryWrapper, Function<? super Object, V> mapper) {
        return null;
    }

    @Override
    public SysUserMapper getBaseMapper() {
        return null;
    }

    @Override
    public Class<SysUser> getEntityClass() {
        return null;
    }
}