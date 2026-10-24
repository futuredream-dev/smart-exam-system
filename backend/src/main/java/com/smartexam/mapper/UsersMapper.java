package com.smartexam.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartexam.entity.Users;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface UsersMapper extends BaseMapper<Users> {
    @Select("SELECT * FROM users WHERE username = #{username} LIMIT 1")
    Users selectByUsername(String username);

    @Select("SELECT * FROM users WHERE username = #{username} LIMIT 1 FOR UPDATE")
    Users selectByUsernameForUpdate(String username);

    @Update("UPDATE users SET password_hash=#{hash}, must_change_password=#{forced}, token_version=token_version+1 WHERE id=#{id}")
    int replacePassword(@Param("id") Long id, @Param("hash") String hash, @Param("forced") boolean forced);

    // MySQL evaluates assignments left to right: calculate from the old status first.
    @Update("UPDATE users SET token_version=token_version+CASE WHEN status=1 AND #{status}=0 THEN 1 ELSE 0 END, status=#{status} WHERE id=#{id}")
    int changeStatus(@Param("id") Long id, @Param("status") int status);
}
