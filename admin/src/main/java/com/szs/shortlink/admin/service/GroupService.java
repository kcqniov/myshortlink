package com.szs.shortlink.admin.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.szs.shortlink.admin.dao.entity.GroupDO;
import com.szs.shortlink.admin.dto.req.ShortLinkGroupSortReqDTO;
import com.szs.shortlink.admin.dto.req.ShortLinkGroupUpdateReqDTO;
import com.szs.shortlink.admin.dto.resp.ShortLinkGroupRespDTO;

import java.util.List;

public interface GroupService extends IService<GroupDO> {
    /**
     * 新增分组
     * @param groupName
     */
    void saveGroup(String groupName);

    void saveGroup(String username, String groupName);

    /**
     * 查询分组
     * @return
     */
    List<ShortLinkGroupRespDTO> listGroup();

    /**
     * 修改短链接分组
     *
     * @param requestParam 修改链接分组参数
     */
    void updateGroup(ShortLinkGroupUpdateReqDTO requestParam);


    /**
     * 删除短链接分组
     *
     * @param gid 短链接分组标识
     */
    void deleteGroup(String gid);

    /**
     * 短链接分组排序
     *
     * @param requestParam 短链接分组排序参数
     */
    void sortGroup(List<ShortLinkGroupSortReqDTO> requestParam);
}
