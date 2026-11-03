package com.abdatytch.media_service.service.impl;

import com.abdatytch.media_service.service.ThumbnailService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

/**
 * Implémentation du service de génération de thumbnails
 * Utilise Java AWT pour redimensionnement haute qualité
 */
@Service
@Slf4j
public class ThumbnailServiceImpl implements ThumbnailService {

    @Value("${media.thumbnail.width:300}")
    private int defaultWidth;

    @Value("${media.thumbnail.height:300}")
    private int defaultHeight;

    @Value("${media.thumbnail.quality:0.8}")
    private float quality;

    @Override
    public Mono<byte[]> generateThumbnail(byte[] imageContent) {
        return generateThumbnail(imageContent, defaultWidth, defaultHeight);
    }

    @Override
    public Mono<byte[]> generateThumbnail(byte[] imageContent, int width, int height) {
        return Mono.fromCallable(() -> {
            try {
                // Lire l'image originale
                BufferedImage originalImage = ImageIO.read(new ByteArrayInputStream(imageContent));
                if (originalImage == null) {
                    log.warn("Not a valid image, cannot generate thumbnail");
                    return imageContent;
                }

                // Calculer les dimensions en conservant le ratio
                int originalWidth = originalImage.getWidth();
                int originalHeight = originalImage.getHeight();
                
                double ratio = Math.min(
                    (double) width / originalWidth,
                    (double) height / originalHeight
                );
                
                int targetWidth = (int) (originalWidth * ratio);
                int targetHeight = (int) (originalHeight * ratio);

                // Créer le thumbnail avec haute qualité
                BufferedImage thumbnail = new BufferedImage(
                    targetWidth,
                    targetHeight,
                    BufferedImage.TYPE_INT_RGB
                );

                Graphics2D g2d = thumbnail.createGraphics();
                
                // Configuration pour haute qualité
                g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                
                g2d.drawImage(originalImage, 0, 0, targetWidth, targetHeight, null);
                g2d.dispose();

                // Convertir en bytes
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ImageIO.write(thumbnail, "jpg", baos);
                byte[] thumbnailBytes = baos.toByteArray();

                log.debug("Thumbnail generated - original: {}x{}, thumbnail: {}x{}, size: {} bytes",
                    originalWidth, originalHeight, targetWidth, targetHeight, thumbnailBytes.length);

                return thumbnailBytes;

            } catch (Exception e) {
                log.error("Failed to generate thumbnail", e);
                return imageContent;
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
