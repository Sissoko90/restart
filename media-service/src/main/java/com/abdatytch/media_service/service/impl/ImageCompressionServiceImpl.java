package com.abdatytch.media_service.service.impl;

import com.abdatytch.media_service.service.ImageCompressionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Iterator;

/**
 * Implémentation du service de compression d'images
 * Optimise le stockage en réduisant la taille des fichiers
 */
@Service
@Slf4j
public class ImageCompressionServiceImpl implements ImageCompressionService {

    @Value("${media.compression.default-quality:0.85}")
    private float defaultQuality;

    @Value("${media.thumbnail.max-image-width:4096}")
    private int maxImageWidth;

    @Value("${media.thumbnail.max-image-height:4096}")
    private int maxImageHeight;

    @Override
    public Mono<byte[]> compressImage(byte[] imageContent) {
        return compressImage(imageContent, defaultQuality);
    }

    @Override
    public Mono<byte[]> compressImage(byte[] imageContent, float quality) {
        return Mono.fromCallable(() -> {
            try {
                BufferedImage image = ImageIO.read(new ByteArrayInputStream(imageContent));
                if (image == null) {
                    log.warn("Not a valid image, skipping compression");
                    return imageContent;
                }

                // Obtenir le writer JPEG
                Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
                if (!writers.hasNext()) {
                    log.warn("No JPEG writer available");
                    return imageContent;
                }

                ImageWriter writer = writers.next();
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ImageOutputStream ios = ImageIO.createImageOutputStream(baos);
                writer.setOutput(ios);

                // Configurer la compression
                ImageWriteParam param = writer.getDefaultWriteParam();
                if (param.canWriteCompressed()) {
                    param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                    param.setCompressionQuality(quality);
                }

                // Écrire l'image compressée
                writer.write(null, new IIOImage(image, null, null), param);

                ios.close();
                writer.dispose();

                byte[] compressedBytes = baos.toByteArray();
                
                float compressionRatio = (1 - (float) compressedBytes.length / imageContent.length) * 100;
                log.debug("Image compressed - original: {} bytes, compressed: {} bytes, ratio: {:.2f}%",
                    imageContent.length, compressedBytes.length, compressionRatio);

                return compressedBytes;

            } catch (Exception e) {
                log.error("Failed to compress image", e);
                return imageContent;
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<byte[]> resizeIfNeeded(byte[] imageContent, int maxWidth, int maxHeight) {
        return Mono.fromCallable(() -> {
            try {
                BufferedImage originalImage = ImageIO.read(new ByteArrayInputStream(imageContent));
                if (originalImage == null) {
                    return imageContent;
                }

                int width = originalImage.getWidth();
                int height = originalImage.getHeight();

                // Vérifier si redimensionnement nécessaire
                if (width <= maxWidth && height <= maxHeight) {
                    log.debug("Image within limits - {}x{}", width, height);
                    return imageContent;
                }

                // Calculer nouvelles dimensions
                double ratio = Math.min(
                    (double) maxWidth / width,
                    (double) maxHeight / height
                );

                int newWidth = (int) (width * ratio);
                int newHeight = (int) (height * ratio);

                // Redimensionner
                BufferedImage resizedImage = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB);
                Graphics2D g2d = resizedImage.createGraphics();
                
                g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                
                g2d.drawImage(originalImage, 0, 0, newWidth, newHeight, null);
                g2d.dispose();

                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ImageIO.write(resizedImage, "jpg", baos);
                byte[] resizedBytes = baos.toByteArray();

                log.info("Image resized - original: {}x{}, resized: {}x{}", width, height, newWidth, newHeight);

                return resizedBytes;

            } catch (Exception e) {
                log.error("Failed to resize image", e);
                return imageContent;
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
