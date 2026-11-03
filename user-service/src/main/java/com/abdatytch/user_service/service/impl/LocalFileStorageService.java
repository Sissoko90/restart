package com.abdatytch.user_service.service.impl;

import com.abdatytch.user_service.constants.FileStorageConstants;
import com.abdatytch.user_service.exception.FileStorageException;
import com.abdatytch.user_service.service.FileStorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

@Service
@Slf4j
public class LocalFileStorageService implements FileStorageService {
    
    @Value("${file.upload.dir}")
    private String uploadDir;
    
    @Value("${file.base.url}")
    private String baseUrl;
    
    // Les deux methodes du monde SERVLET ont ete retirees d'ici et de
    // l'interface : `storeProfilePicture(MultipartFile, String)` et
    // `isValidImage(MultipartFile)`.
    //
    // Ce service est en WebFlux : aucun `MultipartFile` n'y est jamais produit,
    // donc ces deux methodes etaient inatteignables. Et `isValidImage` portait
    // la validation — liste blanche d'extensions, plafond de taille, type de
    // contenu — que le chemin reactif a du reimplementer. La regle existait
    // donc en double, dans une version morte et une version vivante, avec le
    // risque habituel : corriger celle qui ne tourne pas.

    @Override
    public Mono<String> storeFile(FilePart filePart, String userId) {
        log.info(FileStorageConstants.LOG_STORING_PROFILE_PICTURE, userId);

        // L'extension est CHOISIE PARMI LA LISTE BLANCHE, pas reprise du nom
        // fourni par le client.
        //
        // Ce chemin reactif — le seul que le controleur emprunte — ne validait
        // rien du tout. isValidImage() existe bien, applique la liste blanche et
        // la taille maximale, mais sa signature prend un MultipartFile : le type
        // bloquant du monde servlet. Elle n'etait donc appelee que depuis la
        // surcharge bloquante, inatteignable dans ce service WebFlux. Une
        // validation qui existe et qu'on n'atteint jamais ne protege rien —
        // meme nature que les 33 @PreAuthorize inertes rencontres dans
        // auth-service.
        //
        // Concretement, l'extension venait de filePart.filename() tel quel :
        // .svg, .html, .jsp etaient acceptes et ecrits sur le disque. Il n'y a
        // pas de traversee de repertoire possible — lastIndexOf(".") empeche
        // qu'un « .. » figure dans l'extension — mais rien ne limitait les types
        // de fichiers deposes.
        String extension = extensionAutorisee(filePart.filename());
        if (extension == null) {
            log.warn(FileStorageConstants.LOG_WARN_INVALID_EXTENSION, filePart.filename());
            return Mono.error(new FileStorageException(
                    "Type de fichier non autorise. Extensions acceptees : "
                            + FileStorageConstants.ALLOWED_EXTENSIONS));
        }

        return Mono.fromCallable(() -> {
                    Path uploadPath = Paths.get(uploadDir, userId);
                    Files.createDirectories(uploadPath);
                    return uploadPath.resolve(UUID.randomUUID() + extension);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(filePath -> ecrireAvecPlafond(filePart, filePath)
                        .thenReturn(baseUrl + "/" + userId + "/" + filePath.getFileName()))
                .doOnSuccess(fileUrl -> log.info(FileStorageConstants.LOG_PROFILE_PICTURE_STORED, fileUrl))
                .onErrorMap(e -> e instanceof FileStorageException ? e
                        : new FileStorageException(
                                String.format(FileStorageConstants.EX_STORAGE_ERROR, e.getMessage())));
    }

    /**
     * Ecrit le fichier en refusant de depasser {@code MAX_FILE_SIZE}.
     *
     * <p>Le plafond est verifie <b>pendant</b> le transfert, et non avant.
     * L'en-tete {@code Content-Length} d'une partie multipart est fourni par
     * l'appelant : s'y fier laisserait passer un envoi qui annonce 1 Ko et en
     * envoie un gigaoctet. Compter les octets recus est la seule mesure qui ne
     * depende pas de la bonne foi du client.
     *
     * <p>Sans ce plafond, un seul appel pouvait remplir le disque — et ce
     * service ecrit dans le meme systeme de fichiers que les journaux.
     *
     * <p>Le fichier partiel est supprime en cas de depassement : le laisser
     * reviendrait a conserver precisement ce qu'on vient de refuser.
     */
    private Mono<Void> ecrireAvecPlafond(FilePart filePart, Path filePath) {
        AtomicLong recus = new AtomicLong();

        Flux<DataBuffer> borne = filePart.content()
                .handle((buffer, sink) -> {
                    long total = recus.addAndGet(buffer.readableByteCount());
                    if (total > FileStorageConstants.MAX_FILE_SIZE) {
                        DataBufferUtils.release(buffer);
                        sink.error(new FileStorageException(
                                "Fichier trop volumineux. Taille maximale : "
                                        + (FileStorageConstants.MAX_FILE_SIZE / (1024 * 1024)) + " Mo"));
                        return;
                    }
                    sink.next(buffer);
                });

        return DataBufferUtils.write(borne, filePath, StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
                .onErrorResume(e -> supprimerPartiel(filePath).then(Mono.error(e)));
    }

    private Mono<Void> supprimerPartiel(Path filePath) {
        return Mono.fromRunnable(() -> {
                    try {
                        Files.deleteIfExists(filePath);
                    } catch (IOException e) {
                        log.warn("Fichier partiel non supprime : {}", filePath);
                    }
                })
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    /**
     * Rend l'extension si elle figure dans la liste blanche, {@code null} sinon.
     *
     * <p>La valeur rendue est reconstruite depuis la liste blanche et non reprise
     * du nom fourni : cela ecarte d'un coup les variations de casse, les
     * caracteres de controle et tout ce qui suivrait le point.
     */
    private static String extensionAutorisee(String nomFourni) {
        if (nomFourni == null || !nomFourni.contains(".")) {
            return null;
        }
        String candidate = nomFourni.substring(nomFourni.lastIndexOf('.') + 1)
                .toLowerCase(java.util.Locale.ROOT);
        return FileStorageConstants.ALLOWED_EXTENSIONS.contains(candidate)
                ? "." + candidate
                : null;
    }

    @Override
    public void deleteProfilePicture(String fileUrl) {
        if (fileUrl == null || fileUrl.isEmpty()) {
            return;
        }
        
        Path filePath = cheminSurDisque(fileUrl);
        if (filePath == null) {
            log.error(FileStorageConstants.LOG_ERROR_FAILED_TO_DELETE_PATH, fileUrl);
            return;
        }

        try {
            if (Files.deleteIfExists(filePath)) {
                log.info(FileStorageConstants.LOG_PROFILE_PICTURE_DELETED, fileUrl);
            } else {
                // Le fichier absent est journalise, et ne passe plus en silence.
                //
                // L'ancienne version sortait sans rien dire : « supprime » et
                // « introuvable » etaient indistinguables. Sur le chemin d'un
                // effacement RGPD, cela veut dire repondre « fait » en laissant
                // peut-etre la photographie sur le disque.
                log.warn(FileStorageConstants.LOG_WARN_FILE_ABSENT, filePath);
            }
        } catch (IOException e) {
            log.error(FileStorageConstants.LOG_ERROR_FAILED_TO_DELETE, fileUrl, e);
            // Ne pas lancer d'exception, juste logger
        }
    }

    /**
     * Chemin sur disque d'une URL de photo, par ses DEUX derniers segments.
     *
     * <h3>Pourquoi pas un retrait de la base d'URL</h3>
     *
     * La version precedente faisait {@code fileUrl.replace(baseUrl + "/", "")},
     * ce qui liait la suppression a la valeur <b>courante</b> de
     * {@code file.base.url}. Toute URL enregistree sous une base differente ne
     * correspondait plus : le {@code replace} ne remplacait rien, le chemin
     * obtenu etait l'URL entiere, {@code Files.exists} rendait {@code false}, et
     * la methode sortait <b>sans rien supprimer ni rien signaler</b>.
     *
     * <p>Ce n'est pas theorique : la base d'URL vient d'etre corrigee — elle
     * designait le port du service au lieu de la passerelle — et toutes les
     * photos deja enregistrees seraient devenues ineffacables, en silence.
     *
     * <p>Les deux derniers segments sont {@code {userId}/{fichier}}, la forme
     * posee a l'ecriture. Ils ne dependent ni de l'hote, ni du port, ni du
     * prefixe.
     *
     * @return le chemin, ou {@code null} si l'URL n'a pas la forme attendue
     */
    private Path cheminSurDisque(String fileUrl) {
        // La requete et le fragment sont ecartes : ils ne font pas partie du
        // chemin, et « photo.jpg?v=2 » designe le meme fichier.
        String chemin = fileUrl.split("[?#]", 2)[0];
        String[] segments = chemin.split("/");
        if (segments.length < 2) {
            return null;
        }

        String fichier = segments[segments.length - 1];
        String compte = segments[segments.length - 2];
        if (fichier.isBlank() || compte.isBlank()) {
            return null;
        }
        // Traversee de repertoire : un segment « .. » venu d'une URL stockee
        // permettrait de supprimer hors du repertoire d'envoi. La valeur vient
        // de notre base, mais une colonne se modifie.
        if (fichier.contains("..") || compte.contains("..")) {
            return null;
        }

        Path racine = Paths.get(uploadDir).toAbsolutePath().normalize();
        Path cible = racine.resolve(compte).resolve(fichier).normalize();
        // Derniere barriere, independante de l'inspection des segments.
        return cible.startsWith(racine) ? cible : null;
    }
}
