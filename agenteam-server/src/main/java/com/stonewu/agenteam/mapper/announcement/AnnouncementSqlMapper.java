package com.stonewu.agenteam.mapper.announcement;

import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.announcement.entity.AnnouncementRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 公告单表访问，修改前通过映射文件锁定记录。
 */
@Mapper
public interface AnnouncementSqlMapper extends MPJBaseMapper<AnnouncementRow> {

    /**
     * 锁定本企业的公告；平台公告以 {@code null} 传入，对应 {@code enterprise_id IS NULL}。
     */
    AnnouncementRow lockOwned(@Param("enterpriseId") String enterpriseId, @Param("id") String id);

    /**
     * 锁定当前成员可见的公告：本企业公告，或不限企业的平台公告。
     */
    AnnouncementRow lockVisible(@Param("enterpriseId") String enterpriseId, @Param("id") String id);
}
