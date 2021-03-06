package com.tan.labbackend.websocket;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;

import com.tan.labbackend.entity.*;
import com.tan.labbackend.error.GameServerError;
import com.tan.labbackend.interceptor.GameServerException;
import com.tan.labbackend.service.*;
import com.tan.labbackend.utils.MatchCacheUtil;
import com.tan.labbackend.utils.MessageCode;
import com.tan.labbackend.utils.MessageTypeEnum;
import com.tan.labbackend.utils.StatusEnum;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.websocket.*;
import javax.websocket.server.PathParam;
import javax.websocket.server.ServerEndpoint;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * @author yeeq
 * @date 2021/4/9
 */
@Component
@Slf4j
@ServerEndpoint(value = "/game/match/{userId}")
public class ChatWebsocket {

    private Session session;

    private String userId;
    private String contestId;
    static MatchCacheUtil matchCacheUtil;
    static Lock lock = new ReentrantLock();

    static Condition matchCond = lock.newCondition();
    static ExerciseService exerciseService;
    static ScoreService scoreService;
    static ProjectService projectService;
    @Autowired
    public void setMatchCacheUtil(MatchCacheUtil matchCacheUtil) {
        ChatWebsocket.matchCacheUtil = matchCacheUtil;
    }

    @Autowired
    public void setExerciseService(ExerciseService exerciseService) {
        ChatWebsocket.exerciseService = exerciseService;
    }

    @Autowired
    public void setScoreServiceService(ScoreService scoreService) {
        ChatWebsocket.scoreService = scoreService;
    }

    @Autowired
    public void setProjectService(ProjectService projectService) {
        ChatWebsocket.projectService = projectService;
    }

    @OnOpen
    public void onOpen(@PathParam("userId") String userId, Session session) {

        log.info("ChatWebsocket open ?????????????????? userId: {}", userId);

        this.userId = userId;
        this.session = session;
        matchCacheUtil.addClient(userId, this);

        log.info("ChatWebsocket open ?????????????????? userId: {}", userId);
    }

    @OnError
    public void onError(Session session, Throwable error) {

        log.error("ChatWebsocket onError ??????????????? userId: {}, errorMessage: {}", userId, error.getMessage());

        matchCacheUtil.removeClinet(userId);
        matchCacheUtil.removeUserOnlineStatus(userId);
        matchCacheUtil.removeUserFromRoom(userId);
        matchCacheUtil.removeUserMatchInfo(userId);
        matchCacheUtil.removeUserContestInfo(userId);
        log.info("ChatWebsocket onError ?????????????????? userId: {}", userId);
    }

    @OnClose
    public void onClose()
    {
        log.info("ChatWebsocket onClose ???????????? userId: {}", userId);

        matchCacheUtil.removeClinet(userId);
        matchCacheUtil.removeUserOnlineStatus(userId);
        matchCacheUtil.removeUserFromRoom(userId);
        matchCacheUtil.removeUserMatchInfo(userId);
        matchCacheUtil.removeUserContestInfo(userId);

        log.info("ChatWebsocket onClose ?????????????????? userId: {}", userId);
    }

    @OnMessage
    public void onMessage(String message, Session session) {

        log.info("ChatWebsocket onMessage userId: {}, ???????????????????????? message: {}", userId, message);

        JSONObject jsonObject = JSON.parseObject(message);

//        System.out.println(jsonObject);

        MessageTypeEnum type = jsonObject.getObject("type", MessageTypeEnum.class);

//        System.out.println(type.toString());

        log.info("ChatWebsocket onMessage userId: {}, ?????????????????????????????? type: {}", userId, type);

        if (type == MessageTypeEnum.ADD_USER) {
            addUser(jsonObject);
        } else if (type == MessageTypeEnum.MATCH_USER) {
            matchUser(jsonObject);
        } else if (type == MessageTypeEnum.CANCEL_MATCH) {
            cancelMatch(jsonObject);
        } else if (type == MessageTypeEnum.PLAY_GAME) {
            toPlay(jsonObject);
        } else if (type == MessageTypeEnum.GAME_OVER) {
            gameover(jsonObject);
        } else {
            throw new GameServerException(GameServerError.WEBSOCKET_ADD_USER_FAILED);
        }

        log.info("ChatWebsocket onMessage userId: {} ??????????????????", userId);
    }

    /**
     * ????????????
     */
    private void sendMessageAll(MessageReply<?> messageReply) {

        log.info("ChatWebsocket sendMessageAll ?????????????????? userId: {}, messageReply: {}", userId, JSON.toJSONString(messageReply));

        Set<String> receivers = messageReply.getChatMessage().getReceivers();
        for (String receiver : receivers) {
            ChatWebsocket client = matchCacheUtil.getClient(receiver);
            client.session.getAsyncRemote().sendText(JSON.toJSONString(messageReply));
        }

        log.info("ChatWebsocket sendMessageAll ?????????????????? userId: {}", userId);
    }

    /**
     * ????????????????????? jsonObject????????????contestId
     */
    private void addUser(JSONObject jsonObject) {

        log.info("ChatWebsocket addUser ???????????????????????? message: {}, userId: {}", jsonObject.toJSONString(), userId);
        String cid = jsonObject.get("contestId").toString();
        contestId = cid;
        MessageReply<Object> messageReply = new MessageReply<>();
        ChatMessage<Object> result = new ChatMessage<>();
        result.setType(MessageTypeEnum.ADD_USER);
        result.setSender(userId);

        /*
         * ???????????????????????????
         * ?????????????????????????????????????????????????????????????????????????????????????????????????????????????????????
         * ??????????????????
         */
        StatusEnum status = matchCacheUtil.getUserOnlineStatus(userId);
        if (status != null) {
            /*
             * ???????????????????????????????????????????????????????????????????????????
             * ??????????????????????????????
             */
            if (status.compareTo(StatusEnum.GAME_OVER) == 0) {
                messageReply.setCode(MessageCode.SUCCESS.getCode());
                messageReply.setDesc(MessageCode.SUCCESS.getDesc());
                matchCacheUtil.setUserIDLE(userId);
                matchCacheUtil.setUserContestInfo(userId,cid);

            } else {
                messageReply.setCode(MessageCode.USER_IS_ONLINE.getCode());
                messageReply.setDesc(MessageCode.USER_IS_ONLINE.getDesc());
            }
        } else {
            messageReply.setCode(MessageCode.SUCCESS.getCode());
            messageReply.setDesc(MessageCode.SUCCESS.getDesc());
            matchCacheUtil.setUserIDLE(userId);
            matchCacheUtil.setUserContestInfo(userId,cid);
        }

        Set<String> receivers = new HashSet<>();
        receivers.add(userId);
        result.setReceivers(receivers);
        messageReply.setChatMessage(result);

        sendMessageAll(messageReply);

        log.info("ChatWebsocket addUser ???????????????????????? message: {}, userId: {}", jsonObject.toJSONString(), userId);

    }

    /**
     * ????????????????????????
     */
    @SneakyThrows
    private void matchUser(JSONObject jsonObject) {

        log.info("ChatWebsocket matchUser ?????????????????????????????? message: {}, userId: {}", jsonObject.toJSONString(), userId);
        String cid = jsonObject.get("contestId").toString();
        MessageReply<GameMatchInfo> messageReply = new MessageReply<>();
        ChatMessage<GameMatchInfo> result = new ChatMessage<>();
        result.setSender(userId);
        result.setType(MessageTypeEnum.MATCH_USER);

        lock.lock();
        try {
            // ??????????????????????????????
            matchCacheUtil.setUserInMatch(userId);
            matchCond.signal();
        } finally {
            lock.unlock();
        }

        // ??????????????????????????????????????????????????????????????????????????????????????????
        Thread matchThread = new Thread(() -> {
            boolean flag = true;
            String receiver = null;
            String receiver2 = null;
            Integer i =0;
            while (flag) {
                // ?????????????????????????????????????????????
                lock.lock();
                i++;
                try {
                    // ????????????????????????????????????
                    if (matchCacheUtil.getUserOnlineStatus(userId).compareTo(StatusEnum.IN_GAME) == 0
                            || matchCacheUtil.getUserOnlineStatus(userId).compareTo(StatusEnum.GAME_OVER) == 0) {
                        log.info("ChatWebsocket matchUser ???????????? {} ???????????????", userId);
                        return;
                    }
                    // ??????????????????????????????
                    if (matchCacheUtil.getUserOnlineStatus(userId).compareTo(StatusEnum.IDLE) == 0) {
                        // ????????????????????????
                        messageReply.setCode(MessageCode.CANCEL_MATCH_ERROR.getCode());
                        messageReply.setDesc(MessageCode.CANCEL_MATCH_ERROR.getDesc());
                        Set<String> set = new HashSet<>();
                        set.add(userId);
                        result.setReceivers(set);
                        result.setType(MessageTypeEnum.CANCEL_MATCH);
                        messageReply.setChatMessage(result);
                        log.info("ChatWebsocket matchUser ???????????? {} ???????????????", userId);
                        sendMessageAll(messageReply);
                        return;
                    }
                    receiver = matchCacheUtil.getUserInMatchRandom(userId,cid);
                    receiver2 = matchCacheUtil.getUserInMatchRandom(userId,cid,receiver);
                    if (receiver != null&&receiver2!=null) {
                        // ??????????????????????????????
                        if (matchCacheUtil.getUserOnlineStatus(receiver).compareTo(StatusEnum.IN_MATCH) != 0) {
                            log.info("ChatWebsocket matchUser ???????????? {}, ???????????? {} ?????????????????????", userId, receiver);
                        }
                        else if (matchCacheUtil.getUserOnlineStatus(receiver2).compareTo(StatusEnum.IN_MATCH) != 0) {
                            log.info("ChatWebsocket matchUser ???????????? {}, ???????????? {} ?????????????????????", userId, receiver2);
                        }
                        else {
                            matchCacheUtil.setUserInGame(userId);
                            matchCacheUtil.setUserInGame(receiver);
                            matchCacheUtil.setUserInGame(receiver2);
                            matchCacheUtil.setUserInRoom(userId, receiver,receiver2);

                            flag = false;
                        }
                    } else {
                        // ??????????????????????????????????????????????????????
                        try {
                            log.info("ChatWebsocket matchUser ???????????? {} ??????????????????", userId);
                            long nanos = TimeUnit.SECONDS.toNanos(500); // 20s
                            matchCond.awaitNanos(nanos);
                        } catch (InterruptedException e) {
                            log.error("ChatWebsocket matchUser ???????????? {} ????????????: {}",
                                    Thread.currentThread().getName(), e.getMessage());
                        }
                    }
                } finally {
                    lock.unlock();
                }
            }

            UserMatchInfo senderInfo = new UserMatchInfo();
            UserMatchInfo receiverInfo = new UserMatchInfo();
            UserMatchInfo receiver2Info = new UserMatchInfo();
            senderInfo.setUserId(userId);
            senderInfo.setScore(0);
            receiverInfo.setUserId(receiver);
            receiverInfo.setScore(0);
            receiver2Info.setUserId(receiver);
            receiver2Info.setScore(0);

            matchCacheUtil.setUserMatchInfo(userId, JSON.toJSONString(senderInfo));
            matchCacheUtil.setUserMatchInfo(receiver, JSON.toJSONString(receiverInfo));
            matchCacheUtil.setUserMatchInfo(receiver2, JSON.toJSONString(receiver2Info));

            GameMatchInfo gameMatchInfo = new GameMatchInfo();
            // ????????????(5?????????
            Project project = projectService.get(Integer.parseInt(cid));

            List<Exercise> exercises = exerciseService.getQuestionsByCourseId(project.getCourse().getId(),5);

            gameMatchInfo.setExercises(exercises);
            gameMatchInfo.setSelfInfo(senderInfo);
            List<UserMatchInfo> opponentInfo =  new ArrayList<>();
            opponentInfo.add(receiverInfo);
            opponentInfo.add(receiver2Info);
            gameMatchInfo.setOpponentInfo(opponentInfo);

            messageReply.setCode(MessageCode.SUCCESS.getCode());
            messageReply.setDesc(MessageCode.SUCCESS.getDesc());
            // ??????user
            result.setData(gameMatchInfo);
            Set<String> set = new HashSet<>();
            set.add(userId);
            result.setReceivers(set);
            result.setType(MessageTypeEnum.MATCH_USER);
            messageReply.setChatMessage(result);
            sendMessageAll(messageReply);
            // ??????receiver1
            opponentInfo.clear();
            opponentInfo.add(senderInfo);
            opponentInfo.add(receiver2Info);
            gameMatchInfo.setSelfInfo(receiverInfo);
            gameMatchInfo.setOpponentInfo(opponentInfo);

            result.setData(gameMatchInfo);
            set.clear();
            set.add(receiver);
            result.setReceivers(set);
            messageReply.setChatMessage(result);

            sendMessageAll(messageReply);
            // ??????receive2
            opponentInfo.clear();
            opponentInfo.add(senderInfo);
            opponentInfo.add(receiverInfo);
            gameMatchInfo.setSelfInfo(receiver2Info);
            gameMatchInfo.setOpponentInfo(opponentInfo);

            result.setData(gameMatchInfo);
            set.clear();
            set.add(receiver2);
            result.setReceivers(set);
            messageReply.setChatMessage(result);

            sendMessageAll(messageReply);

            log.info("ChatWebsocket matchUser ?????????????????????????????? messageReply: {}", JSON.toJSONString(messageReply));

        }, "MatchTask -- " + userId);
        matchThread.start();
    }

    /**
     * ????????????
     */
    private void cancelMatch(JSONObject jsonObject) {

        log.info("ChatWebsocket cancelMatch ???????????????????????? userId: {}, message: {}", userId, jsonObject.toJSONString());

        lock.lock();
        try {
            matchCacheUtil.setUserIDLE(userId);
        } finally {
            lock.unlock();
        }

        log.info("ChatWebsocket cancelMatch ???????????????????????? userId: {}", userId);
    }

    /**
     * ?????????
     */
    @SneakyThrows
    public void toPlay(JSONObject jsonObject) {

        log.info("ChatWebsocket toPlay ?????????????????????????????? userId: {}, message: {}", userId, jsonObject.toJSONString());

        MessageReply<UserMatchInfo> messageReply = new MessageReply<>();

        ChatMessage<UserMatchInfo> result = new ChatMessage<>();
        result.setSender(userId);
        String[] receivers = matchCacheUtil.getUserFromRoom(userId).split("#");
        String receiver = null;
        String receiver2 = null;


        receiver = receivers[0];
        if(receivers.length>=2){
            receiver2 = receivers[1];
        }

        Set<String> set = new HashSet<>();
        set.add(receiver);
        if(receiver2!=null) {
            set.add(receiver2);
        }
        result.setReceivers(set);
        result.setType(MessageTypeEnum.PLAY_GAME);

        Integer newScore = jsonObject.getInteger("data");
        UserMatchInfo userMatchInfo = new UserMatchInfo();
        userMatchInfo.setUserId(userId);
        userMatchInfo.setScore(newScore);

        matchCacheUtil.setUserMatchInfo(userId, JSON.toJSONString(userMatchInfo));

        result.setData(userMatchInfo);
        messageReply.setCode(MessageCode.SUCCESS.getCode());
        messageReply.setDesc(MessageCode.SUCCESS.getDesc());
        messageReply.setChatMessage(result);

        sendMessageAll(messageReply);

        log.info("ChatWebsocket toPlay ?????????????????????????????? userId: {}, userMatchInfo: {}", userId, JSON.toJSONString(userMatchInfo));
    }

    /**
     * ????????????
     */
    public void gameover(JSONObject jsonObject) {

        log.info("ChatWebsocket gameover ?????????????????? userId: {}, message: {}", userId, jsonObject.toJSONString());
        // ?????????????????????????????????????????????
        MessageReply<UserMatchInfo> messageReply = new MessageReply<>();
        Instant finishTime = Instant.now();
        Integer newScore = jsonObject.getInteger("data");
        UserMatchInfo userMatchInfo = new UserMatchInfo();
        userMatchInfo.setUserId(userId);
        userMatchInfo.setContestId(contestId);
        userMatchInfo.setScore(newScore);
        userMatchInfo.setTime(finishTime);
        matchCacheUtil.setUserMatchInfo(userId, JSON.toJSONString(userMatchInfo));

        ChatMessage<UserMatchInfo> result = new ChatMessage<>();
        result.setSender(userId);
        String[] receivers = matchCacheUtil.getUserFromRoom(userId).split("#");
        String receiver = null;
        String receiver2 = null;
        receiver = receivers[0];
        if(receivers.length>=2){
            receiver2 = receivers[1];
        }
        result.setType(MessageTypeEnum.GAME_OVER);

        lock.lock();
        try {
            matchCacheUtil.setUserGameover(userId);
            if (matchCacheUtil.getUserOnlineStatus(receiver).compareTo(StatusEnum.GAME_OVER) == 0&&matchCacheUtil.getUserOnlineStatus(receiver2).compareTo(StatusEnum.GAME_OVER) == 0) {
                String receiverInfo = matchCacheUtil.getUserMatchInfo(receiver);
                String receiver2Info = matchCacheUtil.getUserMatchInfo(receiver2);
                UserMatchInfo receiverMatchInfo = JSON.parseObject(receiverInfo, UserMatchInfo.class);
                UserMatchInfo receiver2MatchInfo = JSON.parseObject(receiver2Info, UserMatchInfo.class);
                //????????????
                UserMatchInfo[] userMatchInfos = new UserMatchInfo[3];
                userMatchInfos[0] = userMatchInfo;
                userMatchInfos[1] = receiverMatchInfo;
                userMatchInfos[2] = receiver2MatchInfo;
                Arrays.sort(userMatchInfos);
                userMatchInfos[0].setScore(60);
                userMatchInfos[1].setScore(60);
                userMatchInfos[2].setScore(100);

                //????????????
                messageReply.setCode(MessageCode.SUCCESS.getCode());
                messageReply.setDesc(MessageCode.SUCCESS.getDesc());

                result.setData(userMatchInfo);
                messageReply.setChatMessage(result);
                Set<String> set = new HashSet<>();
                set.add(userId);
                result.setReceivers(set);
                sendMessageAll(messageReply);


                result.setData(receiverMatchInfo);
                messageReply.setChatMessage(result);
                set.clear();
                set.add(receiver);
                result.setReceivers(set);
                sendMessageAll(messageReply);


                result.setData(receiver2MatchInfo);
                messageReply.setChatMessage(result);
                set.clear();
                set.add(receiver2);
                result.setReceivers(set);
                sendMessageAll(messageReply);


                matchCacheUtil.removeUserMatchInfo(userId);
                matchCacheUtil.removeUserFromRoom(userId);
                matchCacheUtil.removeUserContestInfo(userId);
                //????????????
                for(UserMatchInfo u :userMatchInfos){
                    UserProject userProject= new UserProject();
                    userProject.setStudentId(Integer.valueOf(u.getUserId()));
                    userProject.setProjectId(Integer.valueOf(u.getContestId()));
                    userProject.setProjectGrade(u.getScore().doubleValue());
                    userProject.setSubmitTime(u.getTime());
                    scoreService.mark(userProject);
                }
            }
        }  finally {
            lock.unlock();
        }
        log.info("ChatWebsocket gameover ?????? [{} - {} - {}] ??????", userId, receiver, receiver2);
    }
}
