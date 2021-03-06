package com.tan.labbackend.service;


import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.mail.MessagingException;
import javax.websocket.OnClose;
import javax.websocket.OnError;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.Session;
import javax.websocket.server.PathParam;
import javax.websocket.server.ServerEndpoint;

import com.alibaba.fastjson.JSONObject;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;


/**
 * @author zhengkai.blog.csdn.net
 */
@ServerEndpoint("/imserver/{userId}")
@Component
public class WebSocketService {

    /**静态变量，用来记录当前在线连接数。应该把它设计成线程安全的。*/
    private static int onlineCount = 0;
    private static Map<Integer, Integer> waitingMap = new ConcurrentHashMap<Integer, Integer>();
    /**concurrent包的线程安全Set，用来存放每个客户端对应的MyWebSocket对象。*/
    private static ConcurrentHashMap<String,WebSocketService> webSocketMap = new ConcurrentHashMap<>();
    /**与某个客户端的连接会话，需要通过它来给客户端发送数据*/
    private Session session;
    /**接收userId*/
    private String userId="";

    /**
     * 连接建立成功调用的方法*/
    @OnOpen
    public void onOpen(Session session,@PathParam("userId") String userId) {
        this.session = session;
        this.userId=userId;
        if(webSocketMap.containsKey(userId)){
            webSocketMap.remove(userId);
            webSocketMap.put(userId,this);
            //加入set中
        }else{
            webSocketMap.put(userId,this);
            //加入set中
            addOnlineCount();
            //在线数加1
        }

        System.out.println("用户连接:"+userId+",当前在线人数为:" + getOnlineCount());

        try {
            sendMessage("连接成功");
        } catch (IOException e) {
            //log.error("用户:"+userId+",网络异常!!!!!!");
        }
    }

    /**
     * 连接关闭调用的方法
     */
    @OnClose
    public void onClose() {
        if(webSocketMap.containsKey(userId)){
            webSocketMap.remove(userId);
            //从set中删除
            subOnlineCount();
        }
        System.out.println("用户退出:"+userId+",当前在线人数为:" + getOnlineCount());
    }

    /**
     * 收到客户端消息后调用的方法
     *
     * @param contestId 客户端发送过来的消息*/
    @OnMessage
    public void onMessage(Integer contestId, Session session) {
        //log.info("用户消息:"+userId+",报文:"+message);
        //可以群发消息
        //消息保存到数据库、redis
        if(waitingMap.containsKey(contestId)){
            Integer value = (Integer) waitingMap.get(contestId);
            waitingMap.put(contestId,value+1);
        }
        else{
            waitingMap.put(contestId,1);
        }
        new Thread(() -> {
            try {
                Integer value = (Integer) waitingMap.get(contestId);
                if(value>=1){
                    JSONObject jsonObject = new JSONObject();
                    //追加发送人(防止串改)
                    jsonObject.put("toUserId",this.userId);
                    String toUserId=jsonObject.getString("toUserId");
                    //传送给对应toUserId用户的websocket
                    if(StringUtils.isNotBlank(toUserId)&&webSocketMap.containsKey(toUserId)){
                        webSocketMap.get(toUserId).sendMessage(jsonObject.toString());
                    }else{
                        //log.error("请求的userId:"+toUserId+"不在该服务器上");
                        //否则不在这个服务器上，发送到mysql或者redis
                    }
                }
                waitingMap.put(contestId,value-1);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    /**
     *
     * @param session
     * @param error
     */
    @OnError
    public void onError(Session session, Throwable error) {
        //log.error("用户错误:"+this.userId+",原因:"+error.getMessage());
        error.printStackTrace();
    }
    /**
     * 实现服务器主动推送
     */
    public void sendMessage(String message) throws IOException {
        this.session.getBasicRemote().sendText(message);
    }


    /**
     * 发送自定义消息
     * */
    public static void sendInfo(String message,@PathParam("userId") String userId) throws IOException {
        //log.info("发送消息到:"+userId+"，报文:"+message);
        if(StringUtils.isNotBlank(userId)&&webSocketMap.containsKey(userId)){
            webSocketMap.get(userId).sendMessage(message);
        }else{
            //log.error("用户"+userId+",不在线！");
        }
    }

    public static synchronized int getOnlineCount() {
        return onlineCount;
    }

    public static synchronized void addOnlineCount() {
        WebSocketService.onlineCount++;
    }

    public static synchronized void subOnlineCount() {
        WebSocketService.onlineCount--;
    }
}

