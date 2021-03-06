package live.blogroom.ws;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshakerFactory;
import io.netty.util.concurrent.GlobalEventExecutor;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.json.JSONObject;

/**
 *
 * @author ugnich
 */
public class HttpHandler extends ChannelInboundHandlerAdapter {
    
    private static Map<Integer, Room> rooms = new ConcurrentHashMap<Integer, Room>();
    int room_id = 0;
    
    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        super.handlerAdded(ctx);
        System.out.println("added");
    }
    
    @Override
    public void handlerRemoved(ChannelHandlerContext context) throws Exception {
        System.out.println("Removed");
        if (room_id > 0) {
            Room r = rooms.get(room_id);
            if (r != null && r.channelGroup != null) {
                r.channelGroup.remove(context.channel());
                broadcastStatus(context);
            }
        }
        super.handlerRemoved(context);
    }
    
    @Override
    public void channelRead(ChannelHandlerContext context, Object msg) throws Exception {
        if (msg instanceof HttpRequest) {
            handleHttp(context, (HttpRequest) msg);
        } else if (msg instanceof TextWebSocketFrame) {
            handleTextFrame(context, (TextWebSocketFrame) msg);
        } else if (msg instanceof PingWebSocketFrame) {
            handlePingFrame(context, (PingWebSocketFrame) msg);
        }
    }
    
    @Override
    public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
        cause.printStackTrace();
        context.close();
    }
    
    private void handleHttp(ChannelHandlerContext context, HttpRequest request) {
        HttpHeaders headers = request.headers();
        if ((headers.get("Connection") != null && headers.get("Connection").equalsIgnoreCase("Upgrade"))
                || (headers.get("Upgrade") != null && headers.get("Upgrade").equalsIgnoreCase("WebSocket"))) {
            try {
                room_id = Integer.parseInt(request.uri().substring("/ws/".length()));
            } catch (Exception e) {
            }
            if (room_id > 0) {
                Room r = rooms.get(room_id);
                if (r == null) {
                    r = new Room(room_id);
                    rooms.put(room_id, r);
                }
                r.channelGroup.add(context.channel());
                handleHandshake(context, request);
                broadcastStatus(context);
                sendHistory(context, r);
            }
        }
    }
    
    private void handleHandshake(ChannelHandlerContext context, HttpRequest request) {
        String wsurl = "ws://" + request.headers().get("Host") + request.uri();
        WebSocketServerHandshakerFactory wsFactory = new WebSocketServerHandshakerFactory(wsurl, null, true);
        WebSocketServerHandshaker handshaker = wsFactory.newHandshaker(request);
        if (handshaker != null) {
            handshaker.handshake(context.channel(), request);
        } else {
            WebSocketServerHandshakerFactory.sendUnsupportedVersionResponse(context.channel());
        }
    }
    
    private void handlePingFrame(ChannelHandlerContext context, PingWebSocketFrame frame) {
        context.channel().writeAndFlush(new PongWebSocketFrame((frame.content())));
    }
    
    private void handleTextFrame(ChannelHandlerContext context, TextWebSocketFrame frame) {
        String txt = frame.text();
        System.out.println("TextWebSocketFrame Received: " + txt);
        broadcast(context, new TextWebSocketFrame(generateJsonMessage(txt)));
        rooms.get(room_id).addHistory(txt);
    }
    
    private void broadcast(ChannelHandlerContext context, WebSocketFrame frame) {
        if (room_id > 0) {
            ChannelGroup cg = rooms.get(room_id).channelGroup;
            for (Channel channel : cg) {
                channel.writeAndFlush(frame.retainedDuplicate());
            }
            System.out.println("  - sent to " + cg.size() + " clients");
        }
    }
    
    private void broadcastStatus(ChannelHandlerContext context) {
        if (room_id > 0) {
            ChannelGroup cg = rooms.get(room_id).channelGroup;
            TextWebSocketFrame statusFrame = new TextWebSocketFrame("{\"status\":{\"online\":" + cg.size() + "}}");
            for (Channel channel : cg) {
                channel.writeAndFlush(statusFrame.retainedDuplicate());
            }
        }
    }
    
    private String generateJsonMessage(String txt) {
        JSONObject jsonRoot = new JSONObject();
        try {
            JSONObject jsonMessage = new JSONObject();
            jsonMessage.put("text", txt);
            jsonRoot.put("message", jsonMessage);
        } catch (Exception e) {
        }
        return jsonRoot.toString();
    }
    
    private void sendHistory(ChannelHandlerContext context, Room room) {
        for (String text : room.history) {
            context.channel().write(new TextWebSocketFrame(generateJsonMessage(text)));
        }
        context.channel().flush();
    }
}
