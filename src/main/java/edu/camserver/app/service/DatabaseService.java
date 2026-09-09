package edu.camserver.app.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class DatabaseService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * Query images with pagination and conditions
     */
    public List<Map<String, Object>> queryImages(int pagesize, String conditions, String order, String lastUID) {
        Map<String, String > headerMapper = Map.of(
                "image", "ImgPath",
                "datetime", "Timestamp",
                "expTime", "ExpTime",
                "eGain", "Gain",
                "siteName", "SiteName",
                "UID", "ImgId",
                "timeZone", "timeZone",
                "humidity", "Humidity",
                "temp", "Temperature"
        );

        String[] fields = {
                "Images.ImgPath",
                "Images.Timestamp",
                "Images.ExpTime",
                "Images.Gain",
                "Cameras.SiteName",
                "Images.ImgId",
                "Images.timeZone",
                "Images.Humidity",
                "Images.Temperature"
        };

        String sql = "SELECT " + String.join(",", fields) +
                " FROM Images INNER JOIN Cameras ON Images.CamId = Cameras.CamId ";

        List<Object> params = new ArrayList<>();

        if (conditions != null || lastUID != null) {
            sql += "WHERE ";
        }
        if (conditions != null) {
            sql += conditions + " ";
        }
        if (lastUID != null) {
            if (conditions != null) {
                sql += "AND ";
            }
            if ("DESC".equalsIgnoreCase(order)) {
                sql += "Images.ImgId < ? ";
            } else {
                sql += "Images.ImgId > ? ";
            }
            params.add(lastUID);
        }

        sql += " ORDER BY Images.Timestamp " + order +
                " OFFSET 0 ROWS FETCH NEXT " + pagesize + " ROWS ONLY";

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, params.toArray());

        // Map rows to header for consistency
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> mapped = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : headerMapper.entrySet()) {
                String header = entry.getKey();
                String key = entry.getValue();
                mapped.put(header, row.getOrDefault(key, null));
            }

            result.add(mapped);

        }
        return result;
    }

    public void insertImage(String imgPath, Map<String, String> spec) {
        String sql = "INSERT INTO Images (ImgPath, CamId, Timestamp, BitDepth, Gain, ExpTime, Humidity, Temperature, TimeZone, IsDayTime) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

        jdbcTemplate.update(sql,
                imgPath,
                spec.get("id"),
                spec.get("date"),
                spec.get("bit"),
                spec.get("gain"),
                spec.get("exp"),
                spec.get("hum"),
                spec.get("temp"),
                spec.get("tz"),
                spec.get("isDay"));
    }

    /**
     * Query all sites from Cameras table
     */
    public List<Map<String, Object>> querySites() {
        String sql = "SELECT UID, SiteName, GeoLoc.Lat AS lat, GeoLoc.Long AS long, CamId FROM Cameras";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> mapped = new LinkedHashMap<>();
            mapped.put("index", row.get("UID"));
            mapped.put("siteName", row.get("SiteName"));
            mapped.put("lat", row.get("lat"));
            mapped.put("long", row.get("long"));
            mapped.put("id", row.get("CamId"));
            result.add(mapped);
        }
        return result;
    }

}
