/*
 * Copyright 2017 original authors
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License. 
 */
package io.micronaut.http.server.netty.multipart;

import io.micronaut.http.MediaType;
import io.micronaut.http.multipart.PartData;
import io.micronaut.http.multipart.StreamingFileUpload;
import io.micronaut.http.multipart.MultipartException;
import io.micronaut.http.server.HttpServerConfiguration;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.ByteBufUtil;
import io.netty.handler.codec.http.multipart.DiskFileUpload;
import io.micronaut.core.async.processor.SingleSubscriberProcessor;
import io.micronaut.core.async.publisher.AsyncSingleResultPublisher;
import io.reactivex.Flowable;
import io.reactivex.Observable;
import io.reactivex.Observer;
import io.reactivex.disposables.Disposable;
import io.reactivex.internal.functions.Functions;
import io.reactivex.internal.operators.flowable.FlowableFromObservable;
import io.reactivex.schedulers.Schedulers;
import io.reactivex.subjects.ReplaySubject;
import io.reactivex.subjects.Subject;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;

/**
 * An implementation of the {@link StreamingFileUpload} interface for Netty
 *
 * @author Graeme Rocher
 * @since 1.0
 */
public class NettyStreamingFileUpload implements StreamingFileUpload {

    private static final Logger LOG = LoggerFactory.getLogger(NettyStreamingFileUpload.class);
    private io.netty.handler.codec.http.multipart.FileUpload fileUpload;
    private final ExecutorService ioExecutor;
    private final HttpServerConfiguration.MultipartConfiguration configuration;
    private final Flowable subject;

    public NettyStreamingFileUpload(
            io.netty.handler.codec.http.multipart.FileUpload httpData,
            HttpServerConfiguration.MultipartConfiguration multipartConfiguration,
            ExecutorService ioExecutor,
            Flowable subject) {
        this.configuration = multipartConfiguration;
        this.fileUpload = httpData;
        this.ioExecutor = ioExecutor;
        this.subject = subject;
    }

    @Override
    public Optional<MediaType> getContentType() {
        return Optional.of(new MediaType(fileUpload.getContentType()));
    }

    @Override
    public String getName() {
        return fileUpload.getName();
    }

    @Override
    public String getFilename() {
        return fileUpload.getFilename();
    }

    @Override
    public long getSize() {
        return fileUpload.definedLength();
    }

    @Override
    public boolean isComplete() {
        return fileUpload.isCompleted();
    }

    @Override
    public Publisher<Boolean> transferTo(String location) {
        String baseDirectory = configuration.getLocation().map(File::getAbsolutePath).orElse(DiskFileUpload.baseDirectory);
        File file = baseDirectory == null ? createTemp(location) : new File(baseDirectory, location);
        return transferTo(file);
    }

    @Override
    public Publisher<Boolean> transferTo(File destination) {
        Supplier<Boolean> transferOperation = () -> {
            try {
                if(LOG.isDebugEnabled()) {
                    LOG.debug("Transferring file {} to location {}", fileUpload.getFilename(), destination);
                }
                return destination != null && fileUpload.renameTo(destination);
            } catch (IOException e) {
                throw new MultipartException("Error transferring file: " + fileUpload.getName(), e);
            }
        };
        if (isComplete()) {
            return new AsyncSingleResultPublisher<>(ioExecutor, transferOperation);
        } else {
            return Observable.<Boolean>create((emitter) -> {

                subject.subscribeOn(Schedulers.from(ioExecutor))
                        .subscribe(Functions.emptyConsumer(),
                                (t) -> emitter.onError((Throwable) t),
                                () -> {
                    if (fileUpload.isCompleted()) {
                        emitter.onNext(transferOperation.get());
                        emitter.onComplete();
                    } else {
                        emitter.onError(new MultipartException("Transfer did not complete"));
                    }
                });

            }).firstOrError().toFlowable();
        }
    }

    @Override
    public Publisher<Boolean> delete() {
        return new AsyncSingleResultPublisher<>(ioExecutor, () -> {
            fileUpload.delete();
            return true;
        });
    }


    protected File createTemp(String location)  {
        File tempFile;
        try {
            tempFile = File.createTempFile(DiskFileUpload.prefix, DiskFileUpload.postfix + '_' + location);
        } catch (IOException e) {
            throw new MultipartException("Unable to create temp directory: " + e.getMessage(), e);
        }
        if (tempFile.delete()) {
            return tempFile;
        }
        return null;
    }

    @Override
    public void subscribe(Subscriber<? super PartData> s) {
        subject.subscribe(s);
    }
}
