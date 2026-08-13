package com.digixmed.cloud.icu.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.lang.Nullable;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

public class HttpUtils {
    /*  17 */   private static final Logger log = LoggerFactory.getLogger(HttpUtils.class);


    public static String doHttpGet(String url) {
        /*  27 */
        RestTemplate restTemplate = new RestTemplate();
        /*  28 */
        String result = null;
        /*  29 */
        result = (String) restTemplate.exchange(url, HttpMethod.GET, null, String.class, new Object[0]).getBody();
        /*  30 */
        return result;
    }


    public static String doHttpPost(String url, MultiValueMap<String, Object> paramMap) {
        /*  41 */
        RestTemplate restTemplate = new RestTemplate();
        /*  42 */
        return (String) restTemplate.postForEntity(url, paramMap, String.class, new Object[0]).getBody();
    }


    public static String doGet(String httpUrl) {
        /*  53 */
        HttpURLConnection connection = null;
        /*  54 */
        InputStream is = null;
        /*  55 */
        BufferedReader br = null;
        /*  56 */
        StringBuffer result = new StringBuffer();

        try {
            /*  59 */
            URL url = new URL(httpUrl);
            /*  60 */
            connection = (HttpURLConnection) url.openConnection();

            /*  62 */
            connection.setRequestMethod("GET");

            /*  64 */
            connection.setConnectTimeout(15000);

            /*  66 */
            connection.setReadTimeout(15000);

            /*  68 */
            connection.connect();

            /*  70 */
            if (connection.getResponseCode() == 200) {

                /*  72 */
                is = connection.getInputStream();
                /*  73 */
                if (is != null) {
                    /*  74 */
                    br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
                    /*  75 */
                    String temp = null;
                    /*  76 */
                    while ((temp = br.readLine()) != null) {
                        /*  77 */
                        result.append(temp);
                    }
                }
            }
            /*  81 */
        } catch (MalformedURLException e) {
            /*  82 */
            log.error(e.getMessage());
            /*  83 */
        } catch (IOException e) {
            /*  84 */
            log.error(e.getMessage());
        } finally {
            /*  86 */
            if (br != null) {
                try {
                    /*  88 */
                    br.close();
                    /*  89 */
                } catch (IOException e) {
                    /*  90 */
                    log.error(e.getMessage());
                }
            }
            /*  93 */
            if (is != null) {
                try {
                    /*  95 */
                    is.close();
                    /*  96 */
                } catch (IOException e) {
                    /*  97 */
                    log.error(e.getMessage());
                }
            }
            /* 100 */
            connection.disconnect();
        }
        /* 102 */
        return result.toString();
    }

    public static Map<String, String> doPost(String httpUrl, @Nullable String param) throws IOException {
        /* 106 */
        HttpURLConnection connection = null;
        /* 107 */
        InputStream is = null;
        /* 108 */
        OutputStream os = null;
        /* 109 */
        BufferedReader br = null;
        /* 110 */
        Map<String, String> result = new HashMap<>();

        log.info("HTTP_POST 请求URL: {}", httpUrl);
        log.info("HTTP_POST 请求体长度: {}", param != null ? param.length() : 0);

        try {
            /* 112 */
            URL url = new URL(httpUrl);

            /* 114 */
            connection = (HttpURLConnection) url.openConnection();

            /* 116 */
            connection.setRequestMethod("POST");

            /* 118 */
            connection.setConnectTimeout(60000);

            /* 120 */
            connection.setReadTimeout(60000);

            /* 122 */
            connection.setDoOutput(true);

            /* 124 */
            connection.setDoInput(true);

            /* 126 */
            connection.setRequestProperty("Content-Type", "text/xml;charset=UTF-8");
            /* 127 */
            connection.setRequestProperty("SOAPAction", "");

            /* 129 */
            os = connection.getOutputStream();
            /* 130 */
            if (param != null) {
                /* 131 */
                os.write(param.getBytes());
            }
            os.flush();

            int responseCode = connection.getResponseCode();
            log.info("HTTP_POST 响应状态码: {}", responseCode);

            /* 134 */
            if (responseCode == 200) {
                /* 135 */
                is = connection.getInputStream();

                /* 137 */
                br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
                /* 138 */
                StringBuffer sbf = new StringBuffer();
                /* 139 */
                String temp = null;

                /* 141 */
                while ((temp = br.readLine()) != null) {
                    /* 142 */
                    sbf.append(temp);
                    /* 143 */
                    sbf.append("\r\n");
                }
                /* 145 */
                result.put("code", "200");
                /* 146 */
                result.put("result", "请求成功");
                /* 147 */
                result.put("success", "true");
                /* 148 */
                result.put("msg", sbf.toString());

                log.info("HTTP_POST 响应体: {}", sbf.toString());
            } else {
                try {
                    /* 151 */
                    is = connection.getErrorStream();

                    /* 153 */
                    br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
                    /* 154 */
                    StringBuffer sbf = new StringBuffer();
                    /* 155 */
                    String temp = null;

                    /* 157 */
                    while ((temp = br.readLine()) != null) {
                        /* 158 */
                        sbf.append(temp);
                        /* 159 */
                        sbf.append("\r\n");
                    }
                    /* 161 */
                    result.put("msg", sbf.toString());
                    /* 162 */

                    log.warn("HTTP_POST 错误响应体: {}", sbf.toString());
                } catch (Exception e) {
                    /* 163 */
                    log.error("HTTP_POST 读取错误响应失败: {}", e.getMessage());
                }

                /* 166 */
                result.put("code", String.valueOf(connection.getResponseCode()));
                /* 167 */
                result.put("result", "请求失败");
                /* 168 */
                result.put("success", "false");

                log.warn("HTTP_POST 请求失败: url={} responseCode={}", httpUrl, connection.getResponseCode());
            }
            /* 170 */
        } catch (MalformedURLException e) {
            /* 171 */
            throw e;
            /* 172 */
        } catch (IOException e) {
            /* 173 */
            throw e;
        } finally {

            /* 176 */
            if (null != br) {
                try {
                    /* 178 */
                    br.close();
                    /* 179 */
                } catch (IOException e) {
                    /* 180 */
                    log.error(e.getMessage());
                }
            }
            /* 183 */
            if (null != os) {
                try {
                    /* 185 */
                    os.close();
                    /* 186 */
                } catch (IOException e) {
                    /* 187 */
                    log.error(e.getMessage());
                }
            }
            /* 190 */
            if (null != is) {
                try {
                    /* 192 */
                    is.close();
                    /* 193 */
                } catch (IOException e) {
                    /* 194 */
                    log.error(e.getMessage());
                }
            }

            /* 198 */
            connection.disconnect();
        }
        /* 200 */
        return result;
    }
}