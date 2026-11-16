package com.shise.studio.common;

import java.util.*;
import org.apache.ibatis.annotations.*;

@Mapper
public interface SqlMapper {
  @SelectProvider(type = Provider.class, method = "sql")
  List<Map<String, Object>> list(@Param("sql") String sql, @Param("p") Map<String, Object> p);

  @UpdateProvider(type = Provider.class, method = "sql")
  int update(@Param("sql") String sql, @Param("p") Map<String, Object> p);

  @InsertProvider(type = Provider.class, method = "sql")
  @Options(useGeneratedKeys = true, keyProperty = "p.id")
  int insert(@Param("sql") String sql, @Param("p") Map<String, Object> p);

  class Provider {
    public static String sql(Map<String, Object> args) {
      return (String) args.get("sql");
    }
  }
}
