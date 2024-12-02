package com.mincai.ikuncode.service.impl;

import cn.hutool.core.lang.UUID;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.mincai.ikuncode.common.Response;
import com.mincai.ikuncode.common.Result;
import com.mincai.ikuncode.config.OSSProperties;
import com.mincai.ikuncode.constant.CaptchaConstant;
import com.mincai.ikuncode.constant.EmailConstant;
import com.mincai.ikuncode.constant.UserConstant;
import com.mincai.ikuncode.constant.UserRole;
import com.mincai.ikuncode.enums.ErrorCode;
import com.mincai.ikuncode.exception.BusinessException;
import com.mincai.ikuncode.mapper.UserMapper;
import com.mincai.ikuncode.model.domain.User;
import com.mincai.ikuncode.model.dto.UserDTO;
import com.mincai.ikuncode.model.vo.UserVO;
import com.mincai.ikuncode.service.EmailService;
import com.mincai.ikuncode.service.UserService;
import com.mincai.ikuncode.utils.RegUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.util.Date;

/**
 * @author limincai
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {

    /**
     * oss 保存图片文件夹
     */
    private final static String IMG_DIRECTORY = "img/";

    @Resource
    OSSProperties ossProperties;

    @Resource
    StringRedisTemplate stringRedisTemplate;

    @Resource
    UserMapper userMapper;

    @Resource
    EmailService emailService;

    @Value("${password.salt}")
    private String salt;

    /**
     * 用户注册
     */
    @Override
    public Response<Long> userRegister(UserDTO userDTO) {
        String userAccount = userDTO.getUserAccount();
        String userEmail = userDTO.getUserEmail();
        String captcha = userDTO.getCaptcha();
        String userPassword = userDTO.getUserPassword();
        String userConfirmedPassword = userDTO.getUserConfirmedPassword();

        // 参数校验
        // 账号为 8 - 16 位不允许带特殊字符
        if (!RegUtil.isLegalUserAccount(userAccount)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "账号或密码格式错误");
        }
        // 密码为 8 - 16 位不允许带特殊字符
        if (!RegUtil.isLegalUserAccount(userPassword)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "账号或密码格式错误");
        }
        // 邮箱格式是否正确
        if (!RegUtil.isLegalUserEmail(userEmail)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "邮箱格式错误");
        }
        // 验证码格式是否正确
        if (!RegUtil.isLegalCaptcha(captcha)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "验证码格式错误");
        }
        // 密码为 8 - 16 位不允许带特殊字符
        if (!RegUtil.isLegalUserPassword(userAccount)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "邮箱格式错误");
        }
        // 密码与确认密码一致
        if (!userPassword.equalsIgnoreCase(userConfirmedPassword)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "密码与确认密码不一致");
        }

        // 验证验证码
        String userRegisterRedisKey = EmailConstant.USER_REGISTER_CAPTCHA_REDIS_KEY + userEmail;
        String redisCaptcha = stringRedisTemplate.opsForValue().get(userRegisterRedisKey);
        if (StringUtils.isEmpty(redisCaptcha)) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "验证码已失效或不存在");
        }
        if (!redisCaptcha.equalsIgnoreCase(captcha)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "验证码错误");
        }

        // 查询数据库是否有相同账号的用户
        LambdaQueryWrapper<User> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(User::getUserAccount, userAccount);
        User user = getOne(queryWrapper);
        if (user != null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户名已存在");
        }

        // 验证码通过删除验证码
        stringRedisTemplate.delete(userRegisterRedisKey);

        // 用户密码加密处理
        String encryptedUserPassword = encryptUserPassword(userPassword);

        // 插入用户
        user = new User();
        user.setUserAccount(userAccount);
        user.setUserPassword(encryptedUserPassword);
        user.setUserEmail(userEmail);
        user.setUserNickname("小黑子" + System.currentTimeMillis());
        save(user);

        return Result.success(user.getUserId());
    }

    /**
     * 用户登陆
     */
    @Override
    public Response<UserVO> userLogin(HttpSession session, UserDTO userDTO) {
        String userAccount = userDTO.getUserAccount();
        String userPassword = userDTO.getUserPassword();
        String userCaptcha = userDTO.getCaptcha();
        String captchaKey = userDTO.getCaptchaKey();

        // 参数校验
        if (!RegUtil.isLegalUserAccount(userAccount) || !RegUtil.isLegalUserPassword(userPassword)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "账号或密码格式错误");
        }
        if (!RegUtil.isLegalCaptcha(userCaptcha)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "验证码格式错误");
        }

        // 查询 redis 验证码是否正确
        String captchaRedisKey = CaptchaConstant.USER_LOGIN_CAPTCHA_REDIS_KEY + captchaKey;
        String captcha = stringRedisTemplate.opsForValue().get(captchaRedisKey);
        if (StringUtils.isEmpty(captcha)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "验证码不存在或失效，请重试");
        }
        if (!captcha.equals(userCaptcha)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "验证码不正确，请重试");
        }

        // 验证码无误，删除验证码缓存
        stringRedisTemplate.delete(captchaRedisKey);

        // 查询数据库账号是否存在或密码输入错误
        LambdaQueryWrapper<User> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(User::getUserAccount, userAccount).eq(User::getUserPassword, encryptUserPassword(userPassword));
        User user = this.getOne(queryWrapper);
        if (user == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "账号不存在或密码错误");
        }

        // 是否为封禁用户
        if (user.getIsDeleted().equals(UserConstant.USER_BANED_CODE)) {
            throw new BusinessException(ErrorCode.FORBIDDEN_ERROR, "账号已被封禁");
        }

        // 将用户转为 userVO，进行脱敏
        UserVO userVO = domain2Dto(user);

        // 记录用户的登陆态
        session.setAttribute(UserConstant.USER_LOGIN_STATE, userVO);

        return Result.success(userVO);
    }

    @Override
    public Response<Void> userLogout(HttpSession session) {
        // 删除用户登陆态
        session.removeAttribute(UserConstant.USER_LOGIN_STATE);
        return Result.success();
    }

    /**
     * 删除用户（要求为管理员）
     */
    @Override
    public Response<Void> userDelete(UserVO loginUserVO, Long deleteUserId) {
        // 是否为超级管理员或管理员
        Integer loginUserRole = loginUserVO.getUserRole();
        if (!loginUserRole.equals(UserRole.SUPER_ADMIN) && !loginUserRole.equals(UserRole.ADMIN)) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        }

        // 当前用户是否存在
        User deleteUser = getById(deleteUserId);
        if (deleteUser == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }

        // 不能删除自己
        Integer deleteUserRole = deleteUser.getUserRole();
        if (loginUserVO.getUserId().equals(deleteUserId)) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "😡，还想逃");
        }

        // 管理员不能删除管理员
        if (loginUserRole.equals(UserRole.ADMIN) && deleteUserRole.equals(UserRole.ADMIN)) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        }

        //超级管理员有最高权限
        if (deleteUserRole.equals(UserRole.SUPER_ADMIN)) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        }

        // 删除用户
        removeById(deleteUserId);
        return Result.success();
    }

    /**
     * 用户修改
     */
    @Override
    public Response<UserVO> userUpdate(HttpSession session, UserVO loginUserVO, UserVO updateUserVO) {
        // 参数校验
        Long userId = updateUserVO.getUserId();
        String userNickname = updateUserVO.getUserNickname();

        // 用户不能修改自己的权限
        if (!loginUserVO.getUserRole().equals(updateUserVO.getUserRole())) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        }

        // 昵称少于 20 位
        if (userNickname.length() > 20) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "昵称过长");
        }

        User updateUser = new User();
        updateUser.setUserId(userId);
        updateUser.setUserNickname(userNickname);
        updateById(updateUser);

        // 将更新后的用户存入 session 中
        loginUserVO.setUserNickname(userNickname);
        session.setAttribute(UserConstant.USER_LOGIN_STATE, loginUserVO);
        return Result.success(loginUserVO);
    }


    @Override
    public Response<UserVO> getLoginUserVO(HttpSession session) {
        // 用户信息是否存在
        UserVO loginUseVO = (UserVO) session.getAttribute(UserConstant.USER_LOGIN_STATE);

        // 用户信息不存在直接返回空
        if (loginUseVO == null) {
            return Result.success(null);
        }

        // 用户信息存在返回
        return Result.success(loginUseVO);
    }

    @Override
    public Response<Void> userRetrievePassword(UserDTO userDTO) {
        String userPassword = userDTO.getUserPassword();
        String userEmail = userDTO.getUserEmail();
        String captcha = userDTO.getCaptcha();
        String userConfirmedPassword = userDTO.getUserConfirmedPassword();

        // 参数校验
        // 密码为 8 - 16 位不允许带特殊字符
        if (!RegUtil.isLegalUserAccount(userPassword)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "密码格式错误");
        }
        // 密码与确认密码一致
        if (!userPassword.equalsIgnoreCase(userConfirmedPassword)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "密码与确认密码不一致");
        }
        // 邮箱格式是否正确
        if (!RegUtil.isLegalUserEmail(userEmail)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "邮箱格式错误");
        }
        // 验证码格式是否正确
        if (!RegUtil.isLegalCaptcha(captcha)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "验证码格式错误");
        }

        // 验证验证码
        String userRetrievePasswordRedisKey = EmailConstant.USER_RETRIEVE_PASSWORD_CAPTCHA_REDIS_KEY + userEmail;
        String redisCaptcha = stringRedisTemplate.opsForValue().get(userRetrievePasswordRedisKey);
        if (StringUtils.isEmpty(redisCaptcha)) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "验证码已失效或不存在");
        }
        if (!redisCaptcha.equalsIgnoreCase(captcha)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "验证码错误");
        }

        // 查询数据库当前邮箱用户是否存在
        LambdaQueryWrapper<User> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(User::getUserEmail, userEmail);
        User user = getOne(queryWrapper);
        if (user == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "当前邮箱未注册用户");
        }

        // 用户新改密码加密处理
        String encryptedUserPassword = encryptUserPassword(userPassword);

        // 修改密码不能与当前密码一致
        if (user.getUserPassword().equals(encryptedUserPassword)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "修改密码不能与当前密码一致");
        }

        // 验证码通过删除验证码
        stringRedisTemplate.delete(userRetrievePasswordRedisKey);

        user.setUserPassword(encryptedUserPassword);

        // 更新用户到数据库
        updateById(user);

        return Result.success();
    }


    @Override
    public String uploadAvatar(MultipartFile multipartFile, Long loginUserId) throws IOException {
        // todo 压缩图片

        // 原文件名
        String originFileName = multipartFile.getOriginalFilename();

        // 验证文件名是否正确
        if (!RegUtil.isLegalPictureFormat(originFileName)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "文件格式不正确，请重试");
        }

        // 上传的文件名
        String fileName = IMG_DIRECTORY + UUID.randomUUID() + originFileName.substring(originFileName.lastIndexOf("."));

        // 上传文件
        OSS ossClient = new OSSClientBuilder().build(ossProperties.getEndpoint(), ossProperties.getAccessKey(), ossProperties.getSecretAccessKey());
        ossClient.putObject(
                //仓库名
                "limincai-ikuncode",
                // 文件名
                fileName,
                // 原文件
                multipartFile.getInputStream());

        //关闭客户端
        ossClient.shutdown();

        // 保存头像地址到数据库
        String avatarUrl = ossProperties.getBucket() + fileName;
        User user = new User();
        user.setUserId(loginUserId);
        user.setUserAvatarUrl(avatarUrl);
        updateById(user);

        // 返回访问路径
        return ossProperties.getBucket() + fileName;
    }


    private UserVO domain2Dto(User user) {
        Long userId = user.getUserId();
        String userAccount = user.getUserAccount();
        Integer userRole = user.getUserRole();
        String userEmail = user.getUserEmail();
        String userNickname = user.getUserNickname();
        String userAvatarUrl = user.getUserAvatarUrl();
        Integer userJijiao = user.getUserJijiao();
        Date createTime = user.getCreateTime();

        UserVO userVO = new UserVO();
        userVO.setUserAccount(userAccount);
        userVO.setUserId(userId);
        userVO.setUserRole(userRole);
        userVO.setUserEmail(userEmail);
        userVO.setUserNickname(userNickname);
        userVO.setUserAvatarUrl(userAvatarUrl);
        userVO.setUserJijiao(userJijiao);
        userVO.setCreateTime(createTime);
        return userVO;
    }

    /**
     * 用户密码加密处理
     */
    private String encryptUserPassword(String userPassword) {
        return DigestUtils.md5DigestAsHex((salt + userPassword).getBytes());
    }

}