package org.dhp.net.nio;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.List;

@Slf4j
public class MessageDecoder {

    protected ByteBuffer buffer;

    public MessageDecoder(int bufferSize) {
        buffer = ByteBuffer.allocate(bufferSize);
    }

    protected int position;
    protected int limit;

    public boolean read(SocketChannel socket, List<NioMessage> list) {
        try {
            position = buffer.position();
            limit = socket.read(buffer);
            //如果读取为-1，说明已经断开
            if (limit == -1) {
                return false;
            }
            ByteBuffer msgBuf = null;
            //写满了缓存
            while (true) {
                if (msgBuf == null) {
                    //获取头
                    int len = buffer.getInt(position);
                    msgBuf = ByteBuffer.allocateDirect(len);
                }
                int remaining = msgBuf.remaining();
                //msgBuf写完整
                if (remaining <= limit - position) {
                    msgBuf.put(buffer.array(), position, remaining);
                    position += remaining;
                    buffer.position(position);

                    NioMessage msg = new NioMessage(msgBuf);
                    list.add(msg);
                    //重置，粘包处理
                    msgBuf.clear();
                    msgBuf = null;
                    //如果下一个包头存在
                    if (limit - position > 4) {
                        continue;
                    } else {
                        position = 0;
                        buffer.position(0);
                    }
                } else {
                    msgBuf.put(buffer.array(), position, remaining);
                    //buffer全部写完
                    buffer.position(0);
                    position = 0;
                }

                //继续写
                limit = socket.read(buffer);
                if (limit <= 0) {
                    break;
                }
            }
            return true;
        } catch (IOException e) {
            log.debug(e.getMessage(), e);
            return false;
        }

    }

    public void destroy() {
    }
}
