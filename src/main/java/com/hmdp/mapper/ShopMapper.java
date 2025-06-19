package com.hmdp.mapper;

import com.hmdp.dto.ShopDTO;
import com.hmdp.entity.Shop;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.lettuce.core.dynamic.annotation.Param;

import java.util.List;

/**
 * <p>
 *  Mapper 接口
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface ShopMapper extends BaseMapper<Shop> {
    List<ShopDTO> selectByDistance(@Param("typeId")  Integer typeId,
                                   @Param("lng")     Double  lng,
                                   @Param("lat")     Double  lat,
                                   @Param("radius")  Double  radius,
                                   @Param("offset")  Integer offset,
                                   @Param("pageSize")Integer pageSize);

}
