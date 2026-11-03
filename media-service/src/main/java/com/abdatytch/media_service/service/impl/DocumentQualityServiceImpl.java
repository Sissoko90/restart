package com.abdatytch.media_service.service.impl;

import com.abdatytch.media_service.service.DocumentQualityService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;

/**
 * Implémentation du service de validation de qualité des documents
 * Analyse la résolution, le contraste et la lisibilité
 * Avec Circuit Breaker et Bulkhead pour résilience
 */
@Service
@Slf4j
public class DocumentQualityServiceImpl implements DocumentQualityService {

    private static final int MIN_WIDTH = 800;
    private static final int MIN_HEIGHT = 600;
    private static final double MIN_QUALITY_SCORE = 70.0;
    private static final int MIN_CONTRAST_THRESHOLD = 30;
    private static final double MIN_LAPLACIAN_VARIANCE = 150.0; // Seuil pour détecter le flou (augmenté pour être plus strict)

    @Override
    public Mono<QualityAnalysisResult> analyzeImageQuality(byte[] imageData, String mimeType) {
        return Mono.fromCallable(() -> {
            log.debug(" Analyzing image quality - Size: {} bytes, MIME: {}", imageData.length, mimeType);
            
            try (ByteArrayInputStream bis = new ByteArrayInputStream(imageData)) {
                BufferedImage image = ImageIO.read(bis);
                
                if (image == null) {
                    log.warn(" Failed to read image - possibly corrupted");
                    return new QualityAnalysisResult(
                        0.0,
                        false,
                        "Image corrompue ou format non supporté",
                        0, 0, 0
                    );
                }
                
                int width = image.getWidth();
                int height = image.getHeight();
                
                log.debug(" Image dimensions: {}x{}", width, height);
                
                double qualityScore = 100.0;
                StringBuilder rejectionReasons = new StringBuilder();
                
                // 1. Vérifier résolution minimale
                if (width < MIN_WIDTH || height < MIN_HEIGHT) {
                    qualityScore -= 40;
                    rejectionReasons.append(String.format(
                        "Résolution insuffisante (%dx%d, minimum requis: %dx%d). ",
                        width, height, MIN_WIDTH, MIN_HEIGHT
                    ));
                    log.warn(" Resolution too low: {}x{}", width, height);
                }
                
                // 2. Détecter le flou avec variance de Laplacien
                double laplacianVar = calculateLaplacianVariance(image);
                if (laplacianVar < MIN_LAPLACIAN_VARIANCE) {
                    qualityScore -= 50;
                    rejectionReasons.append(String.format(
                        "Image floue détectée (variance Laplacien: %.2f, minimum: %.2f). ",
                        laplacianVar, MIN_LAPLACIAN_VARIANCE
                    ));
                    log.warn(" Blurry image detected - Laplacian variance: {}", laplacianVar);
                }
                
                // 3. Analyser le contraste
                double contrastScore = analyzeContrast(image);
                if (contrastScore < MIN_CONTRAST_THRESHOLD) {
                    qualityScore -= 20;
                    rejectionReasons.append(String.format(
                        "Contraste insuffisant (%.1f, minimum: %d). ",
                        contrastScore, MIN_CONTRAST_THRESHOLD
                    ));
                    log.warn(" Low contrast detected: {}", contrastScore);
                }
                
                // 4. Vérifier ratio d'aspect (détection de documents tronqués)
                double aspectRatio = (double) width / height;
                if (aspectRatio < 0.5 || aspectRatio > 3.0) {
                    qualityScore -= 20;
                    rejectionReasons.append(String.format(
                        "Ratio d'aspect anormal (%.2f) - Document possiblement tronqué. ",
                        aspectRatio
                    ));
                    log.warn(" Abnormal aspect ratio: {}", aspectRatio);
                }
                
                // 5. Analyser la luminosité (trop sombre ou trop clair)
                double brightness = analyzeBrightness(image);
                if (brightness < 30 || brightness > 220) {
                    qualityScore -= 15;
                    rejectionReasons.append(String.format(
                        "Luminosité inadéquate (%.1f) - Document trop %s. ",
                        brightness, brightness < 30 ? "sombre" : "clair"
                    ));
                    log.warn(" Poor brightness: {}", brightness);
                }
                
                boolean isReadable = qualityScore >= MIN_QUALITY_SCORE;
                String rejectionReason = isReadable ? null : rejectionReasons.toString().trim();
                
                log.info(" Image quality analysis completed - Score: {}, Readable: {}", 
                    qualityScore, isReadable);
                
                return new QualityAnalysisResult(
                    qualityScore,
                    isReadable,
                    rejectionReason,
                    width,
                    height,
                    1
                );
                
            } catch (Exception e) {
                log.error(" Error analyzing image quality", e);
                return new QualityAnalysisResult(
                    0.0,
                    false,
                    "Erreur lors de l'analyse: " + e.getMessage(),
                    0, 0, 0
                );
            }
        })
        .subscribeOn(Schedulers.boundedElastic());
    }


    @Override
    public Mono<QualityAnalysisResult> analyzePdfQuality(byte[] pdfData) {
        return Mono.fromCallable(() -> {
            log.debug(" Analyzing PDF quality - Size: {} bytes", pdfData.length);
            
            // Pour une implémentation complète, utiliser Apache PDFBox
            // Ici, implémentation simplifiée basée sur la taille
            
            double qualityScore = 100.0;
            StringBuilder rejectionReasons = new StringBuilder();
            
            // 1. Vérifier taille minimale (PDF vide ou corrompu)
            if (pdfData.length < 1024) { // < 1KB
                qualityScore = 0;
                rejectionReasons.append("PDF trop petit ou corrompu. ");
                log.warn(" PDF too small: {} bytes", pdfData.length);
            }
            
            // 2. Vérifier signature PDF
            if (pdfData.length >= 4) {
                String header = new String(pdfData, 0, 4);
                if (!header.equals("%PDF")) {
                    qualityScore = 0;
                    rejectionReasons.append("Signature PDF invalide. ");
                    log.warn(" Invalid PDF signature: {}", header);
                }
            }
            
            // 3. Estimer nombre de pages (simplifié)
            int estimatedPages = estimatePdfPages(pdfData);
            if (estimatedPages == 0) {
                qualityScore -= 50;
                rejectionReasons.append("Impossible de détecter les pages du PDF. ");
                log.warn(" Cannot detect PDF pages");
            }
            
            boolean isReadable = qualityScore >= MIN_QUALITY_SCORE;
            String rejectionReason = isReadable ? null : rejectionReasons.toString().trim();
            
            log.info(" PDF quality analysis completed - Score: {}, Pages: {}, Readable: {}", 
                qualityScore, estimatedPages, isReadable);
            
            return new QualityAnalysisResult(
                qualityScore,
                isReadable,
                rejectionReason,
                0, 0,
                estimatedPages
            );
        })
        .subscribeOn(Schedulers.boundedElastic());
    }


    // ========== Méthodes privées d'analyse ==========

    /**
     * Calcule la variance de Laplacien pour détecter le flou
     * Méthode standard de détection de flou en vision par ordinateur
     * Plus la variance est élevée, plus l'image est nette
     */
    private double calculateLaplacianVariance(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        
        // Convertir en niveaux de gris
        int[][] gray = new int[height][width];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                gray[y][x] = getGrayscale(image.getRGB(x, y));
            }
        }
        
        // Appliquer le filtre de Laplacien
        double sum = 0;
        double sumSquares = 0;
        int count = 0;
        
        for (int y = 1; y < height - 1; y++) {
            for (int x = 1; x < width - 1; x++) {
                // Opérateur de Laplacien 3x3
                int laplacian = 
                    -1 * gray[y-1][x-1] + -1 * gray[y-1][x] + -1 * gray[y-1][x+1] +
                    -1 * gray[y][x-1]   +  8 * gray[y][x]   + -1 * gray[y][x+1] +
                    -1 * gray[y+1][x-1] + -1 * gray[y+1][x] + -1 * gray[y+1][x+1];
                
                sum += laplacian;
                sumSquares += laplacian * laplacian;
                count++;
            }
        }
        
        // Calculer la variance
        double mean = sum / count;
        double variance = (sumSquares / count) - (mean * mean);
        
        log.debug(" Laplacian variance: {}", variance);
        return variance;
    }

    /**
     * Analyse le contraste de l'image
     * Utilise l'écart-type des valeurs de pixels
     */
    private double analyzeContrast(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        
        // Échantillonnage pour performance (max 100x100 pixels)
        int stepX = Math.max(1, width / 100);
        int stepY = Math.max(1, height / 100);
        
        double sum = 0;
        double sumSquares = 0;
        int count = 0;
        
        for (int y = 0; y < height; y += stepY) {
            for (int x = 0; x < width; x += stepX) {
                int rgb = image.getRGB(x, y);
                int gray = getGrayscale(rgb);
                
                sum += gray;
                sumSquares += gray * gray;
                count++;
            }
        }
        
        double mean = sum / count;
        double variance = (sumSquares / count) - (mean * mean);
        double stdDev = Math.sqrt(variance);
        
        // Écart-type élevé = bon contraste
        return stdDev;
    }

    /**
     * Analyse la luminosité moyenne de l'image
     */
    private double analyzeBrightness(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        
        int stepX = Math.max(1, width / 100);
        int stepY = Math.max(1, height / 100);
        
        long sum = 0;
        int count = 0;
        
        for (int y = 0; y < height; y += stepY) {
            for (int x = 0; x < width; x += stepX) {
                int rgb = image.getRGB(x, y);
                sum += getGrayscale(rgb);
                count++;
            }
        }
        
        return (double) sum / count;
    }

    /**
     * Convertit RGB en niveaux de gris
     */
    private int getGrayscale(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        
        // Formule standard de conversion en niveaux de gris
        return (int) (0.299 * r + 0.587 * g + 0.114 * b);
    }

    /**
     * Estime le nombre de pages d'un PDF (méthode simplifiée)
     * Pour une vraie implémentation, utiliser Apache PDFBox
     */
    private int estimatePdfPages(byte[] pdfData) {
        try {
            String content = new String(pdfData);
            // Compter les occurrences de "/Page" dans le PDF
            int count = 0;
            int index = 0;
            while ((index = content.indexOf("/Page", index)) != -1) {
                count++;
                index += 5;
            }
            return Math.max(1, count / 2); // Approximation
        } catch (Exception e) {
            log.warn("Failed to estimate PDF pages", e);
            return 0;
        }
    }
}
