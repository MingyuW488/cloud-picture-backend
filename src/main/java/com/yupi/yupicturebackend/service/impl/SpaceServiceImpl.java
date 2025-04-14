package com.yupi.yupicturebackend.service.impl;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.yupi.yupicturebackend.exception.BusinessException;
import com.yupi.yupicturebackend.exception.ErrorCode;
import com.yupi.yupicturebackend.exception.ThrowUtils;
//import com.yupi.yupicturebackend.manager.sharding.DynamicShardingManager;
import com.yupi.yupicturebackend.model.dto.space.SpaceAddRequest;
import com.yupi.yupicturebackend.model.dto.space.SpaceQueryRequest;
import com.yupi.yupicturebackend.model.entity.Space;
import com.yupi.yupicturebackend.model.entity.SpaceUser;
import com.yupi.yupicturebackend.model.entity.User;
import com.yupi.yupicturebackend.model.enums.SpaceLevelEnum;
import com.yupi.yupicturebackend.model.enums.SpaceRoleEnum;
import com.yupi.yupicturebackend.model.enums.SpaceTypeEnum;
import com.yupi.yupicturebackend.model.vo.SpaceVO;
import com.yupi.yupicturebackend.model.vo.UserVO;
import com.yupi.yupicturebackend.service.SpaceService;
import com.yupi.yupicturebackend.mapper.SpaceMapper;
import com.yupi.yupicturebackend.service.SpaceUserService;
import com.yupi.yupicturebackend.service.UserService;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;

/**
 * 空间Service
 */
@Service
public class SpaceServiceImpl extends ServiceImpl<SpaceMapper, Space>
    implements SpaceService{
    @Resource
    private UserService userService;
    @Resource
    private TransactionTemplate transactionTemplate;
    @Resource
    private SpaceUserService spaceUserService;

//    @Resource 方便部署先取消分表
//    @Lazy
//    private DynamicShardingManager dynamicShardingManager;


    /**
     * 创建空间
     * @param spaceAddRequest
     * @param loginUser
     * @return
     */
    @Override
    public long addSpace(SpaceAddRequest spaceAddRequest, User loginUser) {
        // 填充默认值
        //转换实体类和DTO
        Space space = new Space();
        BeanUtils.copyProperties(spaceAddRequest,space);
        if (StrUtil.isBlank(space.getSpaceName())){
            space.setSpaceName("默认空间");
        }
        if (space.getSpaceLevel() == null){
            space.setSpaceLevel(SpaceLevelEnum.COMMON.getValue());
        }

        if (space.getSpaceType() == null) {
            space.setSpaceType(SpaceTypeEnum.PRIVATE.getValue());
        }
        //填充容量大小
        this.fillSpaceBySpaceLevel(space);

        //校验参数
        this.validSpace(space,true);
        //权限校验
        Long userId = loginUser.getId();
        space.setUserId(userId);
        if (space.getSpaceLevel() != SpaceLevelEnum.COMMON.getValue()){
            if (!userService.isAdmin(loginUser)){
                throw new BusinessException(ErrorCode.NO_AUTH_ERROR,"没有权限");
            }
        }
        //创建空间 一个用户只能创建一个私有空间和一个团队空间
        String lock = String.valueOf(userId).intern();
        synchronized (lock){
            Long newSpaceId = transactionTemplate.execute(status -> {
                //判断是否已经有空间
                boolean exists = this.lambdaQuery()
                        .eq(Space::getUserId, userId)
                        .eq(Space::getSpaceType,spaceAddRequest.getSpaceType())
                        .exists();
                //如果已有，不能创建
                if (exists){
                    throw new BusinessException(ErrorCode.OPERATION_ERROR,"已存在空间,创建失败");
                }
                //否则可以创建，写入数据库
                boolean res = this.save(space);
                ThrowUtils.throwIf(!res, ErrorCode.OPERATION_ERROR,"创建空间失败");
                // 如果是团队空间，关联新增团队成员记录
                if (SpaceTypeEnum.TEAM.getValue() == spaceAddRequest.getSpaceType()) {
                    SpaceUser spaceUser = new SpaceUser();
                    spaceUser.setSpaceId(space.getId());
                    spaceUser.setUserId(userId);
                    spaceUser.setSpaceRole(SpaceRoleEnum.ADMIN.getValue());
                    res = spaceUserService.save(spaceUser);
                    ThrowUtils.throwIf(!res, ErrorCode.OPERATION_ERROR, "创建团队成员记录失败");
                }
                //仅对团队空间生效,创建分表
//                dynamicShardingManager.createSpacePictureTable(space);
                // 返回新写入的数据 id
                return space.getId();
            });

            return newSpaceId;
        }
    }

    /**
     * 获取空间包装类
     * @param space
     * @param request
     * @return
     */
    @Override
    public SpaceVO getSpaceVO(Space space, HttpServletRequest request) {
        SpaceVO spaceVO = SpaceVO.objToVo(space);
        Long userId = space.getUserId();
        //封装脱敏后的用户信息
        if(userId != null & userId > 0){
            User user = userService.getById(userId);
            UserVO userVO = userService.getUserVO(user);
            spaceVO.setUser(userVO);
        }
        return spaceVO;

    }

    /**
     * 分页获取空间包装类
     * @param spacePage
     * @param request
     * @return
     */
    @Override
    public Page<SpaceVO> getSpaceVOPage(Page<Space> spacePage, HttpServletRequest request) {
        List<Space> spaceList = spacePage.getRecords();
        Page<SpaceVO> spaceVOPage = new Page<>(spacePage.getCurrent(),spacePage.getSize(),spacePage.getTotal());
        //如果空间列表为空则返回
        if (CollUtil.isEmpty(spaceList)){
            return spaceVOPage;
        }
        //对象列表 转换为 封装对象列表
        List<SpaceVO> spaceVOList = spaceList.stream().map(SpaceVO::objToVo).collect(Collectors.toList());

        //去重查询用户信息
        List<Long> userIdSet = spaceList.stream().map(Space::getUserId).collect(Collectors.toList());
        Map<Long, List<User>> userIdListMap = userService.listByIds(userIdSet).stream().collect(Collectors.groupingBy(User::getId));
        //为空间VO类封装脱敏后的用户对象
        spaceVOList.forEach(spaceVO -> {
            Long userId = spaceVO.getUserId();
            User user = null;
            if (userIdListMap.containsKey(userId)){
                user = userIdListMap.get(userId).get(0);
            }
            spaceVO.setUser(userService.getUserVO(user));
        });
        spaceVOPage.setRecords(spaceVOList);
        return spaceVOPage;
    }

    /**
     * 设置查询条件
     * @param spaceQueryRequest
     * @return
     */
    @Override
    public QueryWrapper<Space> getQueryWrapper(SpaceQueryRequest spaceQueryRequest) {

        QueryWrapper<Space> queryWrapper = new QueryWrapper<>();
        if (spaceQueryRequest == null){
            return queryWrapper;
        }
        //对象中取值
        Long id = spaceQueryRequest.getId();
        Long userId = spaceQueryRequest.getUserId();
        String spaceName = spaceQueryRequest.getSpaceName();
        Integer spaceLevel = spaceQueryRequest.getSpaceLevel();
        Integer spaceType = spaceQueryRequest.getSpaceType();
        String sortField = spaceQueryRequest.getSortField();
        String sortOrder = spaceQueryRequest.getSortOrder();

        //拼接查询条件
        queryWrapper.eq(ObjUtil.isNotEmpty(id),"id",id);
        queryWrapper.eq(ObjUtil.isNotEmpty(userId),"userId",userId);
        queryWrapper.like(StrUtil.isNotBlank(spaceName),"spaceName",spaceName);
        queryWrapper.eq(ObjUtil.isNotEmpty(spaceLevel),"spaceLevel",spaceLevel);
        queryWrapper.eq(ObjUtil.isNotEmpty(spaceType),"spaceType",spaceType);
        queryWrapper.orderBy(StrUtil.isNotEmpty(sortField), sortOrder.equals("ascend"),sortField);
        return queryWrapper;
    }

    /**
     * 校验空间
     * @param space
     */
    @Override
    public void validSpace(Space space,boolean isAdd) {
     Long id = space.getId();


        ThrowUtils.throwIf(space == null, ErrorCode.PARAMS_ERROR);

        //对象中取值
        String spaceName = space.getSpaceName();
        Integer spaceLevel = space.getSpaceLevel();
        SpaceLevelEnum spaceLevelEnum = SpaceLevelEnum.getEnumByValue(spaceLevel);
        Integer spaceType = space.getSpaceType();
        SpaceTypeEnum spaceTypeEnum = SpaceTypeEnum.getEnumByValue(spaceType);
        // 创建时校验
        if(isAdd){
            ThrowUtils.throwIf(StrUtil.isBlank(spaceName),ErrorCode.PARAMS_ERROR,"空间名称不能为空");
            ThrowUtils.throwIf(spaceLevel == null,ErrorCode.PARAMS_ERROR,"空间级别不能为空");
            ThrowUtils.throwIf(spaceType == null,ErrorCode.PARAMS_ERROR,"空间类型不能为空");

        }

        //修改数据时校验
        if (StrUtil.isNotBlank(spaceName) && spaceName.length() > 30){
            throw new BusinessException(ErrorCode.PARAMS_ERROR,"空间名称不能过长");
        }
        if (spaceLevel != null && spaceLevelEnum == null){
            throw new BusinessException(ErrorCode.PARAMS_ERROR,"空间级别不存在");
        }
        if (spaceType != null && spaceTypeEnum == null){
            throw new BusinessException(ErrorCode.PARAMS_ERROR,"空间类别不存在");
        }

    }

    /**
     * 填充个人空间 容量
     * @param space
     */
    @Override
    public void fillSpaceBySpaceLevel(Space space) {
        Integer spaceLevel = space.getSpaceLevel();
        SpaceLevelEnum spaceLevelEnum = SpaceLevelEnum.getEnumByValue(spaceLevel);
        if (spaceLevelEnum != null){
            long maxSize = spaceLevelEnum.getMaxSize();
            if (space.getMaxSize() == null){
                space.setMaxSize(maxSize);
            }
            long maxCount = spaceLevelEnum.getMaxCount();
            if (space.getMaxCount() == null){
                space.setMaxCount(maxCount);
            }

        }

    }

    /**
     * 校验空间权限
     * @param space
     * @param loginUser
     */
    @Override
    public void checkSpaceAuth(Space space, User loginUser) {
        //仅本人或者管理员有权限
        if (!space.getUserId().equals(loginUser.getId()) && !userService.isAdmin(loginUser)){
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR,"没有权限");
        }

    }
}




