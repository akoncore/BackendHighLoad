package com.akon.catalog.grpc;

import com.akon.catalog.exception.CatalogException;
import com.akon.catalog.model.Product;
import com.akon.catalog.proto.*;
import com.akon.catalog.service.ProductService;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

@Component
public class ProductGrpcService extends ProductServiceGrpc.ProductServiceImplBase {
    private final ProductService service;

    public ProductGrpcService(ProductService service) { this.service = service; }

    @Override
    public void getProduct(GetProductRequest request, StreamObserver<ProductResponse> observer) {
        respond(observer, () -> toProto(service.get(request.getId())));
    }

    @Override
    public void listProducts(ListProductsRequest request, StreamObserver<ListProductsResponse> observer) {
        respond(observer, () -> {
            var page = service.list(request.hasLimit() ? request.getLimit() : 20, request.getCursor());
            var result = ListProductsResponse.newBuilder();
            page.items().forEach(product -> result.addItems(toProto(product)));
            if (page.nextCursor() != null) result.setNextCursor(page.nextCursor());
            return result.build();
        });
    }

    @Override
    public void createProduct(CreateProductRequest request, StreamObserver<ProductResponse> observer) {
        respond(observer, () -> toProto(service.create(request.getIdempotencyKey(), fromProto(request.getProduct()))));
    }

    @Override
    public void updateProduct(UpdateProductRequest request, StreamObserver<ProductResponse> observer) {
        respond(observer, () -> toProto(service.update(request.getId(), request.getVersion(), fromProto(request.getProduct()))));
    }

    @Override
    public void deleteProduct(DeleteProductRequest request, StreamObserver<DeleteProductResponse> observer) {
        respond(observer, () -> {
            service.delete(request.getId());
            return DeleteProductResponse.getDefaultInstance();
        });
    }

    public static ProductResponse toProto(Product product) {
        return ProductResponse.newBuilder().setId(product.id()).setTitle(product.title())
                .setPrice(product.price()).setStock(product.stock()).setVersion(product.version())
                .setCurrency(product.currency()).setCategoryId(product.categoryId())
                .setUpdatedAt(product.updatedAt()).build();
    }

    private com.akon.catalog.model.ProductInput fromProto(ProductInput input) {
        return new com.akon.catalog.model.ProductInput(input.getTitle(), input.getPrice(),
                input.getStock(), input.getCurrency(), input.getCategoryId());
    }

    private <T> void respond(StreamObserver<T> observer, Supplier<T> operation) {
        try {
            if (Context.current().isCancelled()) return;
            T result = operation.get();
            if (Context.current().isCancelled()) return;
            observer.onNext(result);
            observer.onCompleted();
        } catch (CatalogException error) {
            Status status = switch (error.kind()) {
                case INVALID, KEY_REUSED -> Status.INVALID_ARGUMENT;
                case NOT_FOUND -> Status.NOT_FOUND;
                case VERSION_CONFLICT -> Status.ABORTED;
                case PRECONDITION_REQUIRED -> Status.FAILED_PRECONDITION;
            };
            observer.onError(status.withDescription(error.getMessage()).asRuntimeException());
        } catch (DataAccessException error) {
            observer.onError(Status.UNAVAILABLE.withDescription("База данных временно недоступна").asRuntimeException());
        } catch (RuntimeException error) {
            observer.onError(Status.INTERNAL.withDescription("Внутренняя ошибка сервера").asRuntimeException());
        }
    }
}
