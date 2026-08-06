package com.digixmed.cloud.icu.util;

import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import javax.xml.bind.Unmarshaller;


public class XMLUtils {
    public static String convertToXml(Object obj) {
        /*  22 */
        StringWriter sw = new StringWriter();

        try {
            /*  25 */
            JAXBContext context = JAXBContext.newInstance(new Class[]{obj.getClass()});
            /*  26 */
            Marshaller marshaller = context.createMarshaller();

            /*  28 */
            marshaller.setProperty("jaxb.formatted.output", Boolean.TRUE);

            /*  30 */
            marshaller.marshal(obj, sw);
            /*  31 */
        } catch (JAXBException e) {
            /*  32 */
            e.printStackTrace();
        }
        /*  34 */
        return sw.toString();
    }


    public static String convertToXml(Object obj, String path) {
        /*  44 */
        FileWriter fw = null;

        try {
            /*  47 */
            JAXBContext context = JAXBContext.newInstance(new Class[]{obj.getClass()});
            /*  48 */
            Marshaller marshaller = context.createMarshaller();

            /*  50 */
            marshaller.setProperty("jaxb.formatted.output", Boolean.TRUE);


            /*  53 */
            fw = new FileWriter(path);
            /*  54 */
            marshaller.marshal(obj, fw);
            /*  55 */
        } catch (JAXBException e) {
            /*  56 */
            e.printStackTrace();
            /*  57 */
        } catch (IOException e) {
            /*  58 */
            e.printStackTrace();
        }
        /*  60 */
        return fw.toString();
    }


    public static Object convertXmlStrToObject(Class clazz, String xmlStr) {
        /*  71 */
        Object xmlObject = null;
        try {
            /*  73 */
            JAXBContext context = JAXBContext.newInstance(new Class[]{clazz});

            /*  75 */
            Unmarshaller unmarshaller = context.createUnmarshaller();
            /*  76 */
            StringReader sr = new StringReader(xmlStr);
            /*  77 */
            xmlObject = unmarshaller.unmarshal(sr);
            /*  78 */
        } catch (JAXBException e) {
            /*  79 */
            e.printStackTrace();
        }
        /*  81 */
        return xmlObject;
    }


    public static Object convertXmlFileToObject(Class clazz, String xmlPath) {
        /*  92 */
        Object xmlObject = null;
        try {
            /*  94 */
            JAXBContext context = JAXBContext.newInstance(new Class[]{clazz});
            /*  95 */
            Unmarshaller unmarshaller = context.createUnmarshaller();
            /*  96 */
            FileReader fr = null;
            /*  97 */
            fr = new FileReader(xmlPath);
            /*  98 */
            xmlObject = unmarshaller.unmarshal(fr);
            /*  99 */
        } catch (JAXBException e) {
            /* 100 */
            e.printStackTrace();
            /* 101 */
        } catch (FileNotFoundException e) {
            /* 102 */
            e.printStackTrace();
        }
        /* 104 */
        return xmlObject;
    }
}
