package com.smartexam.service;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.smartexam.entity.Users;
import com.smartexam.mapper.UsersMapper;
import org.springframework.stereotype.Service;

@Service
public class UsersService extends ServiceImpl<UsersMapper, Users> {
    public Users findByUsername(String username) { return baseMapper.selectByUsername(username); }
    /** Caller holds the lifecycle transaction through verification and replacement. */
    public Users findByUsernameForUpdate(String username) { return baseMapper.selectByUsernameForUpdate(username); }
    public void replacePassword(Long id, String hash, boolean forced) {
        if (baseMapper.replacePassword(id, hash, forced) != 1) throw new IllegalStateException("Account disappeared during password replacement");
    }
    public void changeStatus(Long id, int status) {
        if (baseMapper.changeStatus(id, status) != 1) throw new IllegalStateException("Account disappeared during status change");
    }
}
