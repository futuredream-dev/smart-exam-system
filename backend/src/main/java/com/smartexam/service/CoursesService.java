package com.smartexam.service;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl; import com.smartexam.entity.Courses; import com.smartexam.mapper.CoursesMapper; import org.springframework.stereotype.Service; import java.util.List;
@Service public class CoursesService extends ServiceImpl<CoursesMapper, Courses> { public List<Courses> findByTeacherId(Long teacherId) { return baseMapper.selectByTeacherId(teacherId); } }
