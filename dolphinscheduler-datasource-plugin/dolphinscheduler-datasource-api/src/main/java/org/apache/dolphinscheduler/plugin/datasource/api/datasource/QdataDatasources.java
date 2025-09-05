package org.apache.dolphinscheduler.plugin.datasource.api.datasource;

import org.apache.dolphinscheduler.common.enums.DbConnectType;

import java.util.List;
import java.util.Map;

import lombok.Data;

@Data
public class QdataDatasources {

    List<Datasource> datasources;

    @Data
    public static class Datasource {

        private String type;
        private String host;
        private Integer port;
        private String userName;
        private String password;
        private String database;
        private DbConnectType connectType;
        private Map<String, String> other;
    }
}
