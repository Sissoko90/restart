package com.abdatytch.media_service.service.impl;

import com.abdatytch.media_service.service.WatermarkService;
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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Implémentation du service de watermarking
 * Ajoute un watermark semi-transparent avec userId + timestamp
 */
@Service
@Slf4j
public class WatermarkServiceImpl implements WatermarkService {

    @Value("${watermark.enabled:true}")
    private boolean watermarkEnabled;

    @Value("${watermark.text:CONFIDENTIAL}")
    private String watermarkText;

    @Value("${watermark.opacity:0.3}")
    private float watermarkOpacity;

    @Override
    public Mono<byte[]> addWatermark(byte[] imageContent, String userId, String documentId) {
        if (!watermarkEnabled) {
            log.debug("Watermarking disabled, returning original image");
            return Mono.just(imageContent);
        }

        return Mono.fromCallable(() -> {
            try {
                // Lire l'image
                BufferedImage originalImage = ImageIO.read(new ByteArrayInputStream(imageContent));
                if (originalImage == null) {
                    log.warn("Not an image, skipping watermark - documentId: {}", documentId);
                    return imageContent;
                }

                // Créer une copie pour le watermark
                BufferedImage watermarkedImage = new BufferedImage(
                    originalImage.getWidth(),
                    originalImage.getHeight(),
                    BufferedImage.TYPE_INT_ARGB
                );

                Graphics2D g2d = watermarkedImage.createGraphics();

                // Dessiner l'image originale
                g2d.drawImage(originalImage, 0, 0, null);

                // Configurer le watermark
                AlphaComposite alphaChannel = AlphaComposite.getInstance(
                    AlphaComposite.SRC_OVER, 
                    watermarkOpacity
                );
                g2d.setComposite(alphaChannel);
                g2d.setColor(Color.WHITE);
                g2d.setFont(new Font("Arial", Font.BOLD, 30));

                // Texte du watermark
                String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
                String watermarkFullText = String.format("%s | User: %s | %s", 
                    watermarkText, userId.substring(0, Math.min(8, userId.length())), timestamp);

                // Calculer position (coin inférieur droit)
                FontMetrics fontMetrics = g2d.getFontMetrics();
                int textWidth = fontMetrics.stringWidth(watermarkFullText);
                int x = watermarkedImage.getWidth() - textWidth - 20;
                int y = watermarkedImage.getHeight() - 20;

                // Dessiner le watermark
                g2d.drawString(watermarkFullText, x, y);

                // Ajouter watermark diagonal au centre (plus discret)
                g2d.rotate(-Math.PI / 4, watermarkedImage.getWidth() / 2.0, watermarkedImage.getHeight() / 2.0);
                g2d.setFont(new Font("Arial", Font.BOLD, 80));
                g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.1f));
                String centerText = "DOC-" + documentId.substring(0, Math.min(8, documentId.length()));
                FontMetrics centerMetrics = g2d.getFontMetrics();
                int centerX = (watermarkedImage.getWidth() - centerMetrics.stringWidth(centerText)) / 2;
                int centerY = watermarkedImage.getHeight() / 2;
                g2d.drawString(centerText, centerX, centerY);

                g2d.dispose();

                // Convertir en bytes
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ImageIO.write(watermarkedImage, "png", baos);
                byte[] watermarkedBytes = baos.toByteArray();

                log.debug("Watermark added successfully - documentId: {}, userId: {}", documentId, userId);

                return watermarkedBytes;

            } catch (Exception e) {
                log.error("Failed to add watermark - documentId: {}", documentId, e);
                return imageContent;
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<Boolean> hasWatermark(byte[] imageContent) {
        return Mono.fromCallable(() -> {
            try {
                BufferedImage image = ImageIO.read(new ByteArrayInputStream(imageContent));
                if (image == null) {
                    return false;
                }
                
                // Vérification simple : chercher des pixels semi-transparents
                // (watermark a une opacité < 1)
                for (int y = image.getHeight() - 50; y < image.getHeight(); y++) {
                    for (int x = image.getWidth() - 200; x < image.getWidth(); x++) {
                        if (x >= 0 && x < image.getWidth() && y >= 0 && y < image.getHeight()) {
                            int pixel = image.getRGB(x, y);
                            int alpha = (pixel >> 24) & 0xff;
                            if (alpha > 0 && alpha < 255) {
                                return true;
                            }
                        }
                    }
                }
                
                return false;
                
            } catch (Exception e) {
                log.error("Failed to check watermark", e);
                return false;
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
