/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.dubbo.remoting.exchange.codec;

import org.apache.dubbo.common.Version;
import org.apache.dubbo.common.io.Bytes;
import org.apache.dubbo.common.io.StreamUtils;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.serialize.Cleanable;
import org.apache.dubbo.common.serialize.ObjectInput;
import org.apache.dubbo.common.serialize.ObjectOutput;
import org.apache.dubbo.common.serialize.Serialization;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.remoting.Channel;
import org.apache.dubbo.remoting.RemotingException;
import org.apache.dubbo.remoting.buffer.ChannelBuffer;
import org.apache.dubbo.remoting.buffer.ChannelBufferInputStream;
import org.apache.dubbo.remoting.buffer.ChannelBufferOutputStream;
import org.apache.dubbo.remoting.exchange.Request;
import org.apache.dubbo.remoting.exchange.Response;
import org.apache.dubbo.remoting.exchange.support.DefaultFuture;
import org.apache.dubbo.remoting.telnet.codec.TelnetCodec;
import org.apache.dubbo.remoting.transport.CodecSupport;
import org.apache.dubbo.remoting.transport.ExceedPayloadLimitException;

import java.io.IOException;
import java.io.InputStream;

/**
 * ExchangeCodec.
 */
public class ExchangeCodec extends TelnetCodec {

    // header length.
    protected static final int HEADER_LENGTH = 16;
    // magic header.
    protected static final short MAGIC = (short) 0xdabb;
    protected static final byte MAGIC_HIGH = Bytes.short2bytes(MAGIC)[0];
    protected static final byte MAGIC_LOW = Bytes.short2bytes(MAGIC)[1];
    // message flag.
    protected static final byte FLAG_REQUEST = (byte) 0x80;
    protected static final byte FLAG_TWOWAY = (byte) 0x40;
    protected static final byte FLAG_EVENT = (byte) 0x20;
    protected static final int SERIALIZATION_MASK = 0x1f;
    private static final Logger logger = LoggerFactory.getLogger(ExchangeCodec.class);

    public Short getMagicCode() {
        return MAGIC;
    }

    @Override
    public void encode(Channel channel, ChannelBuffer buffer, Object msg) throws IOException {
        if (msg instanceof Request) {
            encodeRequest(channel, buffer, (Request) msg);
        } else if (msg instanceof Response) {
            encodeResponse(channel, buffer, (Response) msg);
        } else {
            super.encode(channel, buffer, msg);
        }
    }

    @Override
    public Object decode(Channel channel, ChannelBuffer buffer) throws IOException {
        // 获取数据长度
        int readable = buffer.readableBytes();
        byte[] header = new byte[Math.min(readable, HEADER_LENGTH)];
        // 读取header数据到header数组
        buffer.readBytes(header);
        return decode(channel, buffer, readable, header);
    }

    @Override
    protected Object decode(Channel channel, ChannelBuffer buffer, int readable, byte[] header) throws IOException {
        // check magic number.
        if (readable > 0 && header[0] != MAGIC_HIGH
                || readable > 1 && header[1] != MAGIC_LOW) {
            // 如果当前magic number不合法
            int length = header.length;
            if (header.length < readable) {
                // 创建一个长度为readable的数组，数组内容为header中的值
                header = Bytes.copyOf(header, readable);
                // 将buffer中的剩余部分写入到header
                buffer.readBytes(header, length, readable - length);
            }
            // 从1开始遍历所有的字节（第0个字节在上面已经检查过了），找到magic number
            for (int i = 1; i < header.length - 1; i++) {
                if (header[i] == MAGIC_HIGH && header[i + 1] == MAGIC_LOW) {
                    // magic number之前的数据丢弃
                    buffer.readerIndex(buffer.readerIndex() - header.length + i);
                    // 丢弃的数据保存到header中
                    header = Bytes.copyOf(header, i);
                    break;
                }
            }
            // 到这里已经得到了合法的magic number之前的数据了，这些数据不能被作为正常的request和response解码，这里直接交给父类处理
            return super.decode(channel, buffer, readable, header);
        }
        // check length.
        // 检查当前可读的字节数属否够header的长度
        if (readable < HEADER_LENGTH) {
            // 不够的话继续读取后面的数据
            return DecodeResult.NEED_MORE_INPUT;
        }

        // get data length.
        // 获取保存在header中的数据的长度
        int len = Bytes.bytes2int(header, 12);
        // 检查数据是否超过长度限制，默认8M
        checkPayload(channel, len);

        // len + HEADER_LENGTH为数据的长度+header的长度，即一个完整请求或响应的长度
        int tt = len + HEADER_LENGTH;
        if (readable < tt) {
            // 数据不够的话继续等待数据，通过这种方式也解决了粘包和半包的问题
            return DecodeResult.NEED_MORE_INPUT;
        }

        // limit input stream.
        // 读取数据部分
        ChannelBufferInputStream is = new ChannelBufferInputStream(buffer, len);

        try {
            return decodeBody(channel, is, header);
        } finally {
            if (is.available() > 0) {
                try {
                    if (logger.isWarnEnabled()) {
                        logger.warn("Skip input stream " + is.available());
                    }
                    StreamUtils.skipUnusedStream(is);
                } catch (IOException e) {
                    logger.warn(e.getMessage(), e);
                }
            }
        }
    }

    protected Object decodeBody(Channel channel, InputStream is, byte[] header) throws IOException {
        // flag保存了Req/Res、2 Way、是否为Event、Serialization ID等信息，下面的proto就是Serialization ID
        byte flag = header[2], proto = (byte) (flag & SERIALIZATION_MASK);
        // get request id.
        // 获取request id
        long id = Bytes.bytes2long(header, 4);
        // 如果不是request
        if ((flag & FLAG_REQUEST) == 0) {
            // decode response.
            Response res = new Response(id);
            // 判断是否为event
            if ((flag & FLAG_EVENT) != 0) {
                res.setEvent(true);
            }
            // get status.
            // 获取响应状态码
            byte status = header[3];
            res.setStatus(status);
            try {
                // is保存了响应结果的字节，通过proto定位到序列化实现，再反序列化响应结果
                ObjectInput in = CodecSupport.deserialize(channel.getUrl(), is, proto);
                if (status == Response.OK) {
                    Object data;
                    if (res.isHeartbeat()) {
                        data = decodeHeartbeatData(channel, in);
                    } else if (res.isEvent()) {
                        data = decodeEventData(channel, in);
                    } else {
                        // decodeResponseData的默认实现是in.readObject()
                        data = decodeResponseData(channel, in, getRequestData(id));
                    }
                    // 写入反序列化后的响应结果
                    res.setResult(data);
                } else {
                    // 异常信息是个字符串，这里直接读取就可以
                    res.setErrorMessage(in.readUTF());
                }
            } catch (Throwable t) {
                res.setStatus(Response.CLIENT_ERROR);
                res.setErrorMessage(StringUtils.toString(t));
            }
            return res;
        } else {
            // decode request.
            Request req = new Request(id);
            req.setVersion(Version.getProtocolVersion());
            // 是否需要对请求作出响应
            req.setTwoWay((flag & FLAG_TWOWAY) != 0);
            // 是否是事件请求
            if ((flag & FLAG_EVENT) != 0) {
                req.setEvent(true);
            }
            try {
                // 同上，反序列化请求数据
                ObjectInput in = CodecSupport.deserialize(channel.getUrl(), is, proto);
                Object data;
                if (req.isHeartbeat()) {
                    data = decodeHeartbeatData(channel, in);
                } else if (req.isEvent()) {
                    data = decodeEventData(channel, in);
                } else {
                    data = decodeRequestData(channel, in);
                }
                req.setData(data);
            } catch (Throwable t) {
                // bad request
                req.setBroken(true);
                req.setData(t);
            }
            return req;
        }
    }

    protected Object getRequestData(long id) {
        DefaultFuture future = DefaultFuture.getFuture(id);
        if (future == null) {
            return null;
        }
        Request req = future.getRequest();
        if (req == null) {
            return null;
        }
        return req.getData();
    }

    protected void encodeRequest(Channel channel, ChannelBuffer buffer, Request req) throws IOException {
        Serialization serialization = getSerialization(channel);
        // header.
        // header一共16个字节
        byte[] header = new byte[HEADER_LENGTH];
        // set magic number.
        // 前两个字节为MagicNumber，值为0xdabb
        Bytes.short2bytes(MAGIC, header);

        // set request and serialization flag.
        /*
         第3个字节由4个部分组成：
         Req/Res (1 bit)：Request - 1; Response - 0
         2 Way (1 bit)，是否需要服务端返回值：1为需要，0为不需要
         Event (1 bit)，是否为事件消息，如心跳事件：1为是，0为否
         Serialization ID (5 bit)：当前序列化实现类的id，如fastjson是6，Hessian2是10
         这里因为是对request编码，所以这里设置第一个bit为1，同时和serialization.getContentTypeId()进行|操作，同时设置了Req/Res (1 bit)和Serialization ID (5 bit)的值
         */
        header[2] = (byte) (FLAG_REQUEST | serialization.getContentTypeId());

        // 设置2 Way (1 bit)的值
        if (req.isTwoWay()) {
            header[2] |= FLAG_TWOWAY;
        }
        // 设置Event (1 bit)的值
        if (req.isEvent()) {
            header[2] |= FLAG_EVENT;
        }

        // set request id.
        // 4个字节的request id
        Bytes.long2bytes(req.getId(), header, 4);

        // encode request data.
        // 获取channel当前可以写入的位置
        int savedWriteIndex = buffer.writerIndex();
        // 从写入位置开始偏移16个字节，为header留下位置
        buffer.writerIndex(savedWriteIndex + HEADER_LENGTH);
        // 序列化请求数据到buffer
        ChannelBufferOutputStream bos = new ChannelBufferOutputStream(buffer);
        ObjectOutput out = serialization.serialize(channel.getUrl(), bos);
        if (req.isEvent()) {
            encodeEventData(channel, out, req.getData());
        } else {
            // encodeRequestData默认实现是直接调用out.writeObject(data)，其子类DubboCodec重写了该方法
            encodeRequestData(channel, out, req.getData(), req.getVersion());
        }
        out.flushBuffer();
        if (out instanceof Cleanable) {
            ((Cleanable) out).cleanup();
        }
        bos.flush();
        bos.close();
        // 获取请求数据序列化后的长度
        int len = bos.writtenBytes();
        // 检查请求数据是否超过长度限制，默认8M
        checkPayload(channel, len);
        // 写入请求数据的长度
        Bytes.int2bytes(len, header, 12);

        // write
        // 从channel的savedWriteIndex开始写入header数据
        buffer.writerIndex(savedWriteIndex);
        buffer.writeBytes(header); // write header.
        // 更新channel的写入位置
        buffer.writerIndex(savedWriteIndex + HEADER_LENGTH + len);
    }

    /*
    协议的格式：
    MagicNumber(16 bits)：值固定为0xdabb
    Req/Res (1 bit)：Request - 1; Response - 0
    2 Way (1 bit)，是否需要服务端返回值：1为需要，0为不需要
    Event (1 bit)，是否为事件消息，如心跳事件：1为是，0为否
    Serialization ID (5 bit)：当前序列化实现类的id，如fastjson是6，Hessian2是10
    Status (8 bits)：只在response中有效
        20 - OK
        30 - CLIENT_TIMEOUT
        31 - SERVER_TIMEOUT
        40 - BAD_REQUEST
        50 - BAD_RESPONSE
        60 - SERVICE_NOT_FOUND
        70 - SERVICE_ERROR
        80 - SERVER_ERROR
        90 - CLIENT_ERROR
        100 - SERVER_THREADPOOL_EXHAUSTED_ERROR
    Request ID (64 bits)：请求id
    Data Length (32 bits)：请求数据的字节数
    数据部分，长度等于Data Length字段：这一部分的数据可以看子类DubboCodec的实现
     */
    protected void encodeResponse(Channel channel, ChannelBuffer buffer, Response res) throws IOException {
        // 获取channel的可写入位置的起点
        int savedWriteIndex = buffer.writerIndex();
        try {
            // 获取序列化，默认是Hessian2Serialization
            Serialization serialization = getSerialization(channel);
            // header.
            // header长度为16个字节
            byte[] header = new byte[HEADER_LENGTH];
            // set magic number.
            // 前两个字节为MagicNumber，值为0xdabb
            Bytes.short2bytes(MAGIC, header);
            // set request and serialization flag.
            // 这里没有处理Req/Res (1 bit)位和2 Way，因为当前方法是对response进行编码，Req/Res (1 bit)为0，同时response不需要返回值了
            header[2] = serialization.getContentTypeId();
            // 如果是心跳事件，则设置Event (1 bit)位为1
            if (res.isHeartbeat()) {
                header[2] |= FLAG_EVENT;
            }
            // set response status.
            byte status = res.getStatus();
            // 一个字节的响应结果
            header[3] = status;
            // set request id.
            // 4个字节的request id
            Bytes.long2bytes(res.getId(), header, 4);

            // 从写入位置开始偏移16个字节，为header留下位置
            buffer.writerIndex(savedWriteIndex + HEADER_LENGTH);
            // 序列化响应数据到buffer
            ChannelBufferOutputStream bos = new ChannelBufferOutputStream(buffer);
            ObjectOutput out = serialization.serialize(channel.getUrl(), bos);
            // encode response data or error message.
            if (status == Response.OK) {
                if (res.isHeartbeat()) {
                    encodeEventData(channel, out, res.getResult());
                } else {
                    // encodeResponseData默认实现是直接调用out.writeObject(data)，其子类DubboCodec重写了该方法
                    encodeResponseData(channel, out, res.getResult(), res.getVersion());
                }
            } else {
                // 执行失败直接写入失败原因
                out.writeUTF(res.getErrorMessage());
            }
            out.flushBuffer();
            if (out instanceof Cleanable) {
                ((Cleanable) out).cleanup();
            }
            bos.flush();
            bos.close();

            // 获取响应数据的长度
            int len = bos.writtenBytes();
            // 检查响应数据是否超过长度限制，默认8M
            checkPayload(channel, len);
            // 写入响应数据的长度
            Bytes.int2bytes(len, header, 12);
            // write
            // 从channel的savedWriteIndex开始写入header数据
            buffer.writerIndex(savedWriteIndex);
            buffer.writeBytes(header); // write header.
            // 更新channel的写入位置
            buffer.writerIndex(savedWriteIndex + HEADER_LENGTH + len);
        } catch (Throwable t) {
            // clear buffer
            // 发送异常则清空当前方法写入的数据
            buffer.writerIndex(savedWriteIndex);
            // send error message to Consumer, otherwise, Consumer will wait till timeout.
            if (!res.isEvent() && res.getStatus() != Response.BAD_RESPONSE) {
                Response r = new Response(res.getId(), res.getVersion());
                r.setStatus(Response.BAD_RESPONSE);

                if (t instanceof ExceedPayloadLimitException) {
                    logger.warn(t.getMessage(), t);
                    try {
                        r.setErrorMessage(t.getMessage());
                        // 直接写入一个Response对象，该对象会重新经过编码
                        channel.send(r);
                        return;
                    } catch (RemotingException e) {
                        logger.warn("Failed to send bad_response info back: " + t.getMessage() + ", cause: " + e.getMessage(), e);
                    }
                } else {
                    // FIXME log error message in Codec and handle in caught() of IoHanndler?
                    logger.warn("Fail to encode response: " + res + ", send bad_response info instead, cause: " + t.getMessage(), t);
                    try {
                        r.setErrorMessage("Failed to send response: " + res + ", cause: " + StringUtils.toString(t));
                        channel.send(r);
                        return;
                    } catch (RemotingException e) {
                        logger.warn("Failed to send bad_response info back: " + res + ", cause: " + e.getMessage(), e);
                    }
                }
            }

            // Rethrow exception
            if (t instanceof IOException) {
                throw (IOException) t;
            } else if (t instanceof RuntimeException) {
                throw (RuntimeException) t;
            } else if (t instanceof Error) {
                throw (Error) t;
            } else {
                throw new RuntimeException(t.getMessage(), t);
            }
        }
    }

    @Override
    protected Object decodeData(ObjectInput in) throws IOException {
        return decodeRequestData(in);
    }

    @Deprecated
    protected Object decodeHeartbeatData(ObjectInput in) throws IOException {
        return decodeEventData(null, in);
    }

    protected Object decodeRequestData(ObjectInput in) throws IOException {
        try {
            return in.readObject();
        } catch (ClassNotFoundException e) {
            throw new IOException(StringUtils.toString("Read object failed.", e));
        }
    }

    protected Object decodeResponseData(ObjectInput in) throws IOException {
        try {
            return in.readObject();
        } catch (ClassNotFoundException e) {
            throw new IOException(StringUtils.toString("Read object failed.", e));
        }
    }

    @Override
    protected void encodeData(ObjectOutput out, Object data) throws IOException {
        encodeRequestData(out, data);
    }

    private void encodeEventData(ObjectOutput out, Object data) throws IOException {
        out.writeEvent(data);
    }

    @Deprecated
    protected void encodeHeartbeatData(ObjectOutput out, Object data) throws IOException {
        encodeEventData(out, data);
    }

    protected void encodeRequestData(ObjectOutput out, Object data) throws IOException {
        out.writeObject(data);
    }

    protected void encodeResponseData(ObjectOutput out, Object data) throws IOException {
        out.writeObject(data);
    }

    @Override
    protected Object decodeData(Channel channel, ObjectInput in) throws IOException {
        return decodeRequestData(channel, in);
    }

    protected Object decodeEventData(Channel channel, ObjectInput in) throws IOException {
        try {
            return in.readEvent();
        } catch (IOException | ClassNotFoundException e) {
            throw new IOException(StringUtils.toString("Decode dubbo protocol event failed.", e));
        }
    }

    @Deprecated
    protected Object decodeHeartbeatData(Channel channel, ObjectInput in) throws IOException {
        return decodeEventData(channel, in);
    }

    protected Object decodeRequestData(Channel channel, ObjectInput in) throws IOException {
        return decodeRequestData(in);
    }

    protected Object decodeResponseData(Channel channel, ObjectInput in) throws IOException {
        return decodeResponseData(in);
    }

    protected Object decodeResponseData(Channel channel, ObjectInput in, Object requestData) throws IOException {
        return decodeResponseData(channel, in);
    }

    @Override
    protected void encodeData(Channel channel, ObjectOutput out, Object data) throws IOException {
        encodeRequestData(channel, out, data);
    }

    private void encodeEventData(Channel channel, ObjectOutput out, Object data) throws IOException {
        encodeEventData(out, data);
    }

    @Deprecated
    protected void encodeHeartbeatData(Channel channel, ObjectOutput out, Object data) throws IOException {
        encodeHeartbeatData(out, data);
    }

    protected void encodeRequestData(Channel channel, ObjectOutput out, Object data) throws IOException {
        encodeRequestData(out, data);
    }

    protected void encodeResponseData(Channel channel, ObjectOutput out, Object data) throws IOException {
        encodeResponseData(out, data);
    }

    protected void encodeRequestData(Channel channel, ObjectOutput out, Object data, String version) throws IOException {
        encodeRequestData(out, data);
    }

    protected void encodeResponseData(Channel channel, ObjectOutput out, Object data, String version) throws IOException {
        encodeResponseData(out, data);
    }


}
