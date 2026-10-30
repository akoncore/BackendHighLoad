package com.akon.catalog.config;

import com.akon.catalog.grpc.ProductGrpcService;
import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.net.InetSocketAddress;

@Configuration
public class GrpcConfig {
    @Bean(destroyMethod = "shutdownNow")
    public Server grpcServer(ProductGrpcService service, @Value("${grpc.port}") int port) throws IOException {
        // Учебный сервер доступен только с этого компьютера.
        return NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", port))
                .addService(service).build().start();
    }
}
