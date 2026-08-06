package com.digixmed.cloud.icu.util;

import cn.hutool.core.date.DateUnit;
import cn.hutool.core.date.DateUtil;
import com.digixmed.cloud.icu.service.common.MyConfig;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;

import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class DataUtils {
    /*  21 */   private static final Logger log = LoggerFactory.getLogger(DataUtils.class);


    public static String getSignNameByBedSideCode(String code) {
        /*  25 */
        if (code == null) {
            /*  26 */
            return null;
        }
        /*  28 */
        String result = null;
        /*  29 */
        switch (code) {
            case "param_T":
                /*  31 */
                result = "体温";
                break;
            case "param_HR":
                /*  34 */
                result = "心率";
                break;
            case "param_PR":
                /*  37 */
                result = "脉搏";
                break;
            case "param_resp":
                /*  40 */
                result = "呼吸";
                break;
            case "param_ibp_d":
            case "param_nibp_d":
                /*  44 */
                result = "血压";
                break;
            case "param_niaoLiang":
                /*  47 */
                result = "小便量";
                break;
            case "param_daBianCiShu":
            case "param_daBianAmount":
                /*  51 */
                result = "大便次数";
                break;
            case "param_out_hour_sum":
                /*  54 */
                result = "总出量";
                break;
            case "param_out_other":
                /*  57 */
                result = "其他出量";
                break;
            case "param_in_hour_sum":
                /*  60 */
                result = "总入量";
                break;
        }

        /*  64 */
        return result;
    }

    public static String getUnitByCode(String code) {
        /*  68 */
        if (code == null) {
            /*  69 */
            return null;
        }
        /*  71 */
        String result = null;
        /*  72 */
        switch (code) {
            case "param_T":
            case "param_T_D":
                /*  75 */
                result = "℃";
                break;
            case "param_HR":
            case "param_PR":
            case "param_resp":
                /*  80 */
                result = "次/分";
                break;
            case "param_ibp_d":
            case "param_nibp_d":
                /*  84 */
                result = "mmHg";
                break;
            case "param_niaoLiang":
            case "param_out_hour_sum":
            case "param_out_other":
            case "param_in_hour_sum":
                /*  90 */
                result = "ml";
                break;
            case "param_daBianAmount":
            case "param_daBianCiShu":
                /*  94 */
                result = "次";
                break;
        }

        /*  98 */
        return result;
    }

    public static String getSignCodeByCode(String code) {
        /* 102 */
        if (code == null) {
            /* 103 */
            return null;
        }
        /* 105 */
        String result = null;
        /* 106 */
        switch (code) {
            case "param_T":
                /* 108 */
                result = "1001";
                break;
            case "param_PR":
                /* 111 */
                result = "1002";
                break;
            case "param_HR":
                /* 114 */
                result = "1003";
                break;
            case "param_resp":
                /* 117 */
                result = "1004";
                break;
            case "param_ibp_d":
            case "param_nibp_d":
                /* 121 */
                result = "1005";
                break;
            case "param_daBianAmount":
            case "param_daBianCiShu":
                /* 125 */
                result = "1007";
                break;
            case "param_niaoLiang":
                /* 128 */
                result = "1008";
                break;
            case "param_in_hour_sum":
                /* 131 */
                result = "1009";
                break;
            case "param_out_hour_sum":
                /* 134 */
                result = "1010";
                break;
            case "param_out_other":
                /* 137 */
                result = "1035";
                break;
        }

        /* 141 */
        return result;
    }

    public static String getClassCodeCode(String code) {
        /* 144 */
        if (code == null) {
            /* 145 */
            return null;
        }
        /* 147 */
        String result = null;
        /* 148 */
        switch (code) {
            case "param_T":
            case "param_T_D":
            case "param_PR":
            case "param_HR":
            case "param_analgesia_cpot_score":
                /* 154 */
                result = "A";
                break;
            case "param_resp":
                /* 157 */
                result = "E";
                break;
            case "param_ibp_d":
            case "param_nibp_d":
            case "param_niaoLiang":
            case "param_daBianAmount":
            case "param_大便量g":
            case "param_daBianCiShu":
            case "param_out":
            case "param_out_other":
            case "param_in":
                /* 168 */
                result = "B";
                break;
        }

        /* 172 */
        return result;
    }

    public static <T> Predicate<T> distinctByKey(Function<? super T, ?> keyExtractor) {
        /* 176 */
        Map<Object, Boolean> seen = new ConcurrentHashMap<>();
        /* 177 */
        return t -> (seen.putIfAbsent(keyExtractor.apply(t), Boolean.TRUE) == null);
    }

    public static Double roundDouble(Double data) {
        /* 181 */
        if (data == null) {
            /* 182 */
            return Double.valueOf(0.0D);
        }
        /* 184 */
        Double result = data;
        /* 185 */
        BigDecimal b = new BigDecimal(data.doubleValue());
        /* 186 */
        result = Double.valueOf(b.setScale(2, 4).doubleValue());
        /* 187 */
        return result;
    }


    public static Date getTomorrow7clock(Date today) {
        /* 192 */
        Calendar calendar = Calendar.getInstance();
        /* 193 */
        calendar.setTime(today);
        /* 194 */
        calendar.add(5, 1);
        /* 195 */
        return calendar.getTime();
    }

    public static String round(Double data, String defualt) {
        /* 199 */
        if (data == null) {
            /* 200 */
            return defualt;
        }
        /* 202 */
        DecimalFormat df = new DecimalFormat("#" + defualt);
        /* 203 */
        String result = df.format(data);
        /* 204 */
        return result;
    }

    public static String round1(Double data, String defualt) {
        /* 207 */
        if (data == null) {
            /* 208 */
            return defualt;
        }
        /* 210 */
        DecimalFormat df = new DecimalFormat("0.0");
        /* 211 */
        String result = df.format(data);
        /* 212 */
        String endStr = result.substring(result.length() - 1);
        /* 213 */
        if ("0".equals(endStr)) {
            /* 215 */
            return result.substring(0, result.length() - 2);
        }
        /* 217 */
        return result;
    }

    public static String round2(Double data, String defualt) {
        /* 220 */
        if (data == null) {
            /* 221 */
            return defualt;
        }
        /* 223 */
        DecimalFormat df = new DecimalFormat("0.000");
        /* 224 */
        String result = df.format(data);
        /* 225 */
        return result.substring(0, result.length() - 1);
    }

    public static String round3(Double data, String defualt) {
        /* 228 */
        if (data == null) {
            /* 229 */
            return defualt;
        }
        /* 231 */
        DecimalFormat df = new DecimalFormat("0.00");
        /* 232 */
        String result = df.format(data);

        /* 234 */
        String endStr = result.substring(result.length() - 1);
        /* 235 */
        if ("0".equals(endStr)) {
            /* 236 */
            String substring = result.substring(0, result.length() - 1);

            /* 238 */
            String end = substring.substring(substring.length() - 1);
            /* 239 */
            if ("0".equals(end)) {
                /* 241 */
                return substring.substring(0, substring.length() - 2);
            }
            /* 243 */
            return substring;
        }
        /* 245 */
        return result;
    }

    public static long getHourBeforeNextDay(Date timePoint) {
        /* 249 */
        Date nextTimePoint = getNextTimePoint(timePoint);
        /* 250 */
        long times = DateUtil.between(nextTimePoint, timePoint, DateUnit.HOUR);
        /* 251 */
        return times;
    }


    public static Date getNextTimePoint(Date time) {
        /* 256 */
        Date oclock = DateUtils.getTimeByOclock(time, Integer.valueOf(MyConfig.TIMPOINTASD));
        /* 257 */
        if (oclock.before(time)) {
            /* 258 */
            oclock = getTomorrow7clock(oclock);
        }
        /* 260 */
        return oclock;
    }

    public static String getRequestStr(String dataStr) {
        /* 264 */
        return "<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\" xmlns:ws=\"http://ws.nis.ewell/\">\n   <soapenv:Header/>\n   <soapenv:Body>\n      <ws:xmlReqEwellByGet>\n         <!--Optional:-->\n         <inputXml><![CDATA[" + dataStr + "]]></inputXml>\n      </ws:xmlReqEwellByGet>\n   </soapenv:Body>\n</soapenv:Envelope>";
    }


    public static <T> T getValueFromDocByKey(Document doc, String path, Class<T> clazz) {
        /* 276 */
        if (doc == null) {
            /* 277 */
            return null;
        }
        /* 279 */
        Object result = null;
        try {
            /* 281 */
            if (path.contains(".")) {
                /* 282 */
                String[] keys = path.split("\\.");
                /* 283 */
                for (String key : keys) {
                    /* 284 */
                    if (doc.get(key) == null) {
                        break;
                    }
                    /* 287 */
                    if (doc.get(key) instanceof Document) {
                        /* 288 */
                        doc = (Document) doc.get(key, Document.class);
                    } else {
                        /* 290 */
                        result = doc.get(key, clazz);
                    }
                }
            } else {
                /* 294 */
                result = doc.get(path, clazz);
            }

            /* 297 */
        } catch (Exception e) {
            /* 298 */
            log.error(path + "报错：" + path);
        }
        /* 300 */
        if (result != null) {
            /* 301 */
            if (result instanceof String && (
                    /* 302 */         (String) result).isEmpty()) {
                /* 303 */
                return null;
            }

            /* 306 */
            return (T) result;
        }
        /* 308 */
        return null;
    }


    public static String calculateProfit(double doubleValue) {
        /* 319 */
        DecimalFormat df = new DecimalFormat("#.0000");
        /* 320 */
        String result = df.format(doubleValue);


        /* 323 */
        String index = result.substring(0, 1);

        /* 325 */
        if (".".equals(index)) {
            /* 326 */
            result = "0" + result;
        }


        /* 330 */
        int inde = firstIndexOf(result, ".");


        /* 333 */
        return result.substring(0, inde + 3);
    }


    public static int firstIndexOf(String str, String pattern) {
        /* 343 */
        for (int i = 0; i < str.length() - pattern.length(); i++) {
            /* 344 */
            int j = 0;
            /* 345 */
            while (j < pattern.length() &&
                    /* 346 */         str.charAt(i + j) == pattern.charAt(j)) {
                /* 348 */
                j++;
            }
            /* 350 */
            if (j == pattern.length())
                /* 351 */ return i;
        }
        /* 353 */
        return -1;
    }

    public static Integer getNumberOfDecimalPlace(Double value) {
        /* 357 */
        BigDecimal bigDecimal = new BigDecimal("" + value);
        /* 358 */
        String str = bigDecimal.toPlainString();
        /* 359 */
        int index = str.indexOf('.');
        /* 360 */
        if (index < 0) {
            /* 361 */
            return Integer.valueOf(0);
        }
        /* 363 */
        return Integer.valueOf(str.length() - 1 - index);
    }
}
