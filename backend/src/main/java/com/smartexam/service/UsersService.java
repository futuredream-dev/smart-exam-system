package com.smartexam.service;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl; import com.smartexam.entity.Users; import com.smartexam.mapper.UsersMapper; import org.springframework.stereotype.Service;
@Service public class UsersService extends ServiceImpl<UsersMapper, Users> { public Users findByUsername(String username) { return baseMapper.selectByUsername(username); } }
