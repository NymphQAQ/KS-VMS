package cn.ks.vms;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Getter
@Setter
@TableName("visit")
class Visit {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long previousId;
    private int version;
    private Long siteId;
    private Long submittedGateId;
    private Long hostId;
    private String requestedHost;
    private Long departmentId;
    private Long creatorId;
    private Long invitationId;
    private Long identityId;
    private String source;
    private String channel;
    private Policy.Type type;
    private String state;
    private String name;
    private String phoneKey;
    private String guestKey;
    private String contactPhone;
    private String company;
    private String reason;
    private String plate;
    private String referenceNo;
    private Long areaId;
    private Instant plannedIn;
    private Instant plannedOut;
    private Instant windowStart;
    private Instant windowEnd;
    private int maxMinutes;
    private Long safetyNoticeId;
    private Long privacyNoticeId;
    private Instant trainingUntil;
    private Instant permitUntil;
    private String permitNo;
    private String rejection;
    private Instant submittedAt;
}

@Mapper
interface VisitMapper extends BaseMapper<Visit> {
    @Select("SELECT * FROM visit WHERE id=#{id} FOR UPDATE")
    Visit lock(long id);
}
