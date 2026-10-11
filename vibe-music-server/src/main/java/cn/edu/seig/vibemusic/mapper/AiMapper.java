package cn.edu.seig.vibemusic.mapper;

import cn.edu.seig.vibemusic.model.entity.ChatMessage;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AiMapper extends BaseMapper<ChatMessage> {

}
