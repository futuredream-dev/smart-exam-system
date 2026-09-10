package com.smartexam.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper; import com.smartexam.entity.Users; import org.apache.ibatis.annotations.Mapper; import org.apache.ibatis.annotations.Select;
@Mapper public interface UsersMapper extends BaseMapper<Users> { @Select("SELECT * FROM users WHERE username = #{username} LIMIT 1") Users selectByUsername(String username); }
