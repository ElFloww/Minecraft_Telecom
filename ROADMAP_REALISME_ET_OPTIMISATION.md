# Telecom Mod : feuille de route de realisme et d'optimisation

Date de reference : 15 septembre 2026.

## 1. Objectif et statut du document

Transformer le mod en un simulateur de telecommunications inspire des reseaux francais : construire les infrastructures, raccorder des abonnes, fournir des services, exploiter le reseau et diagnostiquer ses pannes, en solo comme sur serveur multijoueur.

Le realisme recherche concerne les causes et les consequences : un mauvais brassage, une liaison saturee, une perte optique excessive ou une collecte interrompue doivent produire des effets explicables. Il ne s'agit pas de reproduire chaque paquet et chaque procedure des normes au prix des performances du serveur.

Ce document est un backlog cible, pas une liste de fonctionnalites deja disponibles. Les cases restent ouvertes jusqu'a implementation et validation. Il a ete etabli a partir de l'analyse statique du depot ; le suivi ci-dessous distingue maintenant les travaux livres des objectifs restants.

### Suivi de mise en oeuvre

**Premier lot J0 livre le 15 septembre 2026. La roadmap complete et le jalon J0 restent en cours.**

| Domaine | Avancement concret |
| --- | --- |
| Compatibilite | Migration Java vers les API Minecraft 1.21.11 / NeoForge 21.11.42 : registres, blocs, NBT, interfaces et paquets. Ajout des 17 descriptions d'items attendues par le client. |
| Sauvegardes | Codec du graphe avec schema versionne et lecture de l'ancien format ; migration ValueInput/ValueOutput des entites. Protection contre le remplacement d'un fichier existant illisible. |
| Securite Minecraft | Parametres des tests calcules cote serveur, portee/type/spectateur verifies, durees et chaines bornees, cadence limitee, une session manuelle par joueur et 256 sessions par graphe. |
| Mobile | Baux IPv4 persistants par UUID et dimension ; agregation limitee a l'antenne de service. Le modele radio complet et les SIM ne sont pas implementes. |
| HTTP | Loopback configurable, token pour mutations et acces distant, controles Host/Origin, jobs serveur, instantanes detaches, files et caches bornes, aucune generation de chunks par les tuiles. |
| Optimisation initiale | Cache de chemins limite a 1 024 entrees ; arret du calcul de propagation lorsque le signal est deja indetectable ; suppression de la sauvegarde inutile au lancement d'une session temporaire. |
| Restauration des blocs | Un noeud deja connu ne declenche plus de recalcul a chaque onLoad ; le recalcul des cables est lie a leur pose/destruction reelle plutot qu'au rechargement de leur entite. |
| Frontend et CI | Sources Vite avec lockfile, tests dashboard, bundle reconstruit ; tests JUnit et GameTests ajoutes a la CI. Documentation dans README.md. |

**Validation deja realisee :** compilation propre, tests JUnit de graphe/codecs/ressources/block entities/HTTP et tests GameTest de pose, rupture, reparation, retrait et lecture du graphe depuis le disque. Les tests HTTP tournent avec de vraies connexions locales et des objets monde simules. Les GameTests utilisent seulement `build/gametest/`.

Resultat final du lot : **48 tests JUnit reussis avec token de test**, **4 tests dashboard reussis** et **4 GameTests Telecom reussis** (plus un test vanilla). Verification HTTP sans token egalement reussie, avec les deux cas authentifies ignores dans ce mode. La protection des fichiers invalides est testee sur disque, y compris apres tentative de sauvegarde. Le test de restauration des blocs recree leurs entites et rappelle onLoad ; il ne remplace pas un essai de dechargement physique complet de chunk.

Commandes de validation : `TELECOM_HTTP_TOKEN=local-test-token ./gradlew build runGameTestServer --no-daemon`, `env -u TELECOM_HTTP_TOKEN ./gradlew test --rerun --tests '*TelecomHttpServerTest'`, `npm --prefix web-dashboard test`. Le token ci-dessus est uniquement une valeur de test, pas un secret de deploiement.

**Restent ouverts dans J0 :** recette visuelle client et connexion multijoueur interactive, redemarrage complet de mondes existants, comportement general hors chunks charges, adressage fixe stable, allocation de congestion unifiee, mesures et budgets de performance. Les API ne disposent pas encore de roles operateurs ; les lectures distantes Minecraft restent accessibles suivant le comportement existant.

Les cases cochees ci-dessous correspondent uniquement aux sous-taches terminees. Une implementation partielle mentionnee dans ce journal ne suffit pas a cocher une exigence plus large.

### Priorites

| Niveau | Signification |
| --- | --- |
| P0 | Stabilite, securite, integrite des sauvegardes et mesures de performance. A traiter avant l'expansion du mod. |
| P1 | Fondations de simulation et premiere boucle de jeu complete. |
| P2 | Approfondissement du realisme et exploitation a l'echelle d'une ville. |
| P3 | Extensions expertes ou specialisees, desactivables independamment. |

Les identifiants des taches sont stables et peuvent servir a creer des issues. Leur ordre dans une section ne remplace pas les dependances de livraison definies en fin de document.

### Principes non negociables

- Le serveur Minecraft fait autorite sur les equipements, les droits, les capacites et les resultats.
- Une capacite affichee correspond a une ressource effectivement prise en compte par le moteur.
- Une infrastructure passive n'est pas un routeur et n'a pas besoin d'une IP pour transporter un signal.
- Une couverture radio ne garantit ni authentification, ni collecte, ni acces a un service.
- Un chunk decharge ne signifie pas automatiquement une panne de telecommunications.
- Une fonctionnalite couteuse possede un budget, une limite et une strategie de degradation controlee.
- Le reseau de telecommunications simule reste distinct de la connexion Minecraft reelle des joueurs.
- La simplicite d'utilisation et la profondeur technique sont proposees par des profils, pas par des moteurs contradictoires.

## 2. Base existante a conserver et verifier

Le depot contient deja des cables cuivre et fibre, des routeurs, des serveurs, des antennes multibandes, des NRO/NRA/PM/SR, un graphe persistant, des sessions de trafic, un smartphone, des outils de diagnostic et un tableau de bord HTTP.

Les NRO/NRA/PM/SR sont encore largement des noeuds generiques. L'adressage, la propagation, l'agregation radio et la congestion sont simplifies. Les boutons Web et SMS du smartphone sont des emplacements non implementes. L'existence d'un ecran ou d'un compteur ne prouve pas la presence du mecanisme metier correspondant.

| Zone | Fichiers de reference |
| --- | --- |
| Versions et construction | `gradle.properties`, `build.gradle`, `.github/workflows/` |
| Catalogue de blocs | `src/main/java/com/florentdubut/telecom/registry/ModBlocks.java` |
| Graphe, trafic et chemins | `src/main/java/com/florentdubut/telecom/network/TelecomNetworkGraph.java` |
| Decouverte et adressage | `src/main/java/com/florentdubut/telecom/network/NetworkTracer.java` |
| Propagation radio | `src/main/java/com/florentdubut/telecom/network/SignalPropagator.java` |
| Synchronisation et scan mobile | `src/main/java/com/florentdubut/telecom/network/ModNetworking.java` |
| Bandes radio | `src/main/java/com/florentdubut/telecom/network/TelecomFrequency.java` |
| Serveur web | `src/main/java/com/florentdubut/telecom/server/TelecomHttpServer.java` |
| Interfaces | `src/main/java/com/florentdubut/telecom/client/gui/`, `web-dashboard/` |

## 3. P0 : stabiliser et securiser l'existant

### Compatibilite et cycle de vie

- [x] **BASE-01** Valider les versions Minecraft, NeoForge, mappings et Java declarees ; corriger les incompatibilites d'API par une compilation propre.
- [ ] **BASE-02** Verifier le demarrage du client, d'un monde solo, d'un serveur dedie sans classes client chargees et d'une connexion multijoueur.
- [ ] **BASE-03** Tester pose, remplacement, destruction, explosion, chargement et dechargement des equipements sans noeud fantome ni suppression abusive du graphe.
- [ ] **BASE-04** Supprimer les etats statiques fuyant entre mondes ou sessions ; isoler explicitement les dimensions et les instances de serveur.
- [ ] **BASE-05** Fermer proprement HTTP, executors, files de travail et caches a l'arret ; verifier plusieurs ouvertures et fermetures de mondes dans le meme client.

### Securite Minecraft

- [ ] **SEC-01** Valider chaque paquet client : format, taille, valeurs finies, bornes, dimension, type d'equipement et operation autorisee.
- [ ] **SEC-02** Determiner cote serveur les limites de debit, l'identite d'abonne, la source, les bandes et la duree autorisee des tests ; ne pas faire confiance aux valeurs fournies par le client.
- [ ] **SEC-03** Verifier distance et contexte d'interaction pour les actions locales ; exiger un droit explicite pour les actions distantes.
- [ ] **SEC-04** Ajouter des quotas par joueur et par serveur pour les scans, tests, configurations et demandes de carte ; borner les sessions simultanees.
- [ ] **SEC-05** Controler propriete et roles avant lecture sensible ou modification ; ne pas diffuser les mesures individuelles a tous les joueurs.

### Securite web

- [x] **WEB-01** Rendre le tableau de bord optionnel, avec adresse et port configurables ; conserver une ecoute locale par defaut et aucune exposition publique implicite.
- [ ] **WEB-02** Ajouter authentification et roles distincts de consultation, exploitation et administration ; stocker les secrets hors des ressources publiques et des journaux.
- [x] **WEB-03** Limiter CORS aux origines autorisees ; proteger les actions modifiantes contre les requetes intersites et verifier la methode HTTP.
- [ ] **WEB-04** Borner corps, parametres, temps de traitement, files et concurrence ; renvoyer des erreurs explicites sans traces internes ni secrets.
- [x] **WEB-05** Servir uniquement les ressources statiques autorisees ; normaliser les chemins et empecher l'acces a des ressources hors de l'espace web public.
- [x] **WEB-06** Executer les commandes de mutation sur le thread serveur avec reponse asynchrone ou identifiant d'operation ; exposer des instantanes immuables pour les lectures.
- [x] **WEB-07** Ne jamais charger ou generer arbitrairement des chunks a la demande d'une tuile ; limiter l'acces aux donnees de terrain deja disponibles ou preparees.
- [ ] **WEB-08** Serialiser les identifiants longs comme chaines JSON ; inclure dimension et identifiant stable pour eviter collisions et pertes de precision JavaScript.
- [x] **WEB-09** Retourner un statut de test exact : accepte, refuse, deja actif, source inaccessible ou aucun serveur joignable ; ne pas annoncer un succes inconditionnel.
- [x] **WEB-10** Documenter une exposition distante via un reverse proxy TLS et des controles d'acces ; ne pas confondre CORS et authentification.

### Corrections du moteur actuel

- [ ] **FIX-01** Remplacer l'attribution actuelle des IP : supprimer les compteurs ambigus, les collisions, les octets hors limites et l'utilisation de `0.0.0.0` comme adresse de plusieurs serveurs.
- [ ] **FIX-02** Remplacer les IP mobiles derivees des coordonnees ou de l'identifiant temporaire du joueur par des allocations persistantes liees aux sessions d'abonnes.
- [ ] **FIX-03** Ne plus reattribuer toutes les IP lors d'un recalcul de topologie ; invalider uniquement les dependances affectees.
- [ ] **FIX-04** Corriger l'agregation automatique de toutes les antennes recues ; distinguer cellule de service, porteuses compatibles et ressources de chaque site.
- [ ] **FIX-05** Unifier les capacites utilisees par le calcul, les outils et le tableau de bord ; supprimer les valeurs d'affichage sans effet dans le moteur.
- [ ] **FIX-06** Corriger le comptage de congestion pour qu'une session ne consomme pas deux fois la meme ressource et que la capacite ne depende pas de l'ordre de parcours.
- [ ] **FIX-07** Distinguer downlink, uplink, full-duplex et ressources partagees ; ne pas additionner sans distinction tous les sens de trafic.
- [ ] **FIX-08** Mesurer les tests sur une fenetre : moyenne, pic et percentiles pertinents ; ne plus enregistrer uniquement le dernier echantillon.
- [ ] **FIX-09** Definir clairement duree totale et duree par phase des tests ; terminer proprement une session en cas de deconnexion ou de chemin rompu.
- [ ] **FIX-10** Remplacer la carte du meilleur signal historique par des mesures datees ; representer les degradations, l'absence de couverture et les donnees perimees.
- [ ] **FIX-11** Unifier les unites : bit/s, octet, ms, dB, dBm, MHz et longueur ; choisir des types evitant les debordements lors des agregations.
- [ ] **FIX-12** Decider du sort des fonctions non exposees et des anciens fichiers : finaliser ou retirer les gestionnaires et ressources obsoletes, sans les presenter comme operationnels.

**Validation P0 :** un client non autorise ne peut pas modifier un equipement ; un appel web ne modifie pas directement le monde hors thread serveur ; un redemarrage conserve les identites ; aucun endpoint de carte ne provoque une generation libre du monde.

## 4. P1 : modele de donnees et architecture de simulation

### Separation des couches

- [ ] **ARCH-01** Separer inventaire physique, circuits, reseau logique, abonnes, services et presentation. Les interfaces lisent le modele, elles ne reinventent pas ses regles.
- [ ] **ARCH-02** Introduire des identifiants stables pour equipement, port, cable, fibre, circuit, cellule, abonne et session. Une IP n'est pas une identite d'equipement.
- [ ] **ARCH-03** Representer des ports explicites avec technologie, direction, compatibilite, capacite et etat administratif/operationnel.
- [ ] **ARCH-04** Representer plusieurs fibres ou paires independantes dans un cable ; reserver et liberer une ressource sans connecter automatiquement tous les conducteurs voisins.
- [ ] **ARCH-05** Distinguer proximite visuelle, continuite physique, synchronisation technique, authentification et disponibilite applicative.
- [ ] **ARCH-06** Definir une machine d'etats commune : installe, hors tension, initialise, synchronise, authentifie, en service, degrade, en panne et maintenance, selon le type d'equipement.
- [ ] **ARCH-07** Separer plan de controle et plan de donnees : configuration et convergence d'un cote, allocation de trafic de l'autre.
- [ ] **ARCH-08** Utiliser des evenements metier pour les changements de topologie, d'energie, de configuration et d'abonnement ; eviter les rescans globaux systematiques.
- [ ] **ARCH-09** Centraliser les profils techniques, commerciaux et radio dans des donnees configurables avec validation ; fournir des valeurs par defaut documentees.
- [ ] **ARCH-10** Definir les frontieres entre dimensions ; toute liaison interdimensionnelle doit etre une extension explicite, pas une collision de coordonnees.

### Persistance

- [ ] **DATA-01** Versionner les donnees sauvegardees et fournir des migrations testees pour les mondes existants ; sauvegarder avant toute migration destructive.
- [ ] **DATA-02** Ne pas persister les technologies par position dans une enumeration ; utiliser des identifiants nommes stables et gerer les profils inconnus.
- [ ] **DATA-03** Definir une source de verite pour chaque donnee partagee entre BlockEntity et graphe ; implementer une reconciliation idempotente.
- [ ] **DATA-04** Distinguer donnees persistantes, mesures temporaires et caches reconstruisibles ; ne pas sauvegarder les compteurs de trafic a chaque tick.
- [ ] **DATA-05** Borner et compacter l'historique ; prevoir retention, agregation temporelle et archivage configurable.
- [ ] **DATA-06** Tester interruptions de sauvegarde, donnees invalides et referentiels manquants ; isoler et signaler une entree corrompue sans perdre tout le reseau.
- [ ] **DATA-07** Partitionner la persistance par region ou composant lorsque les mesures montrent que le fichier global devient un goulot ; eviter la reecriture complete de tres grands graphes.

### Moteur de trafic

- [ ] **SIM-01** Simuler des flux plutot que chaque paquet par defaut ; decrire demande, sens, route, classe de service, duree et volume.
- [ ] **SIM-02** Definir les ressources consommees : port, circuit, collecte, port PON, cellule radio, equipement et plafond commercial.
- [ ] **SIM-03** Implementer une allocation partagee deterministe, par exemple une equite max-min ponderee ; repartir la capacite restante apres les premiers goulots.
- [ ] **SIM-04** Garantir debit alloue non negatif, somme des allocations inferieure a chaque capacite et respect des limitations de bout en bout.
- [ ] **SIM-05** Modeliser latence de propagation, traitement, file d'attente, gigue et pertes ; separer latence aller et aller-retour.
- [ ] **SIM-06** Utiliser des files bornees et des politiques de service explicites ; traiter congestion, priorites et surcharge sans croissance illimitee de memoire.
- [ ] **SIM-07** Definir le comportement des flux apres panne, changement de route, handover, fermeture de service et retour a la normale.
- [ ] **SIM-08** Ajouter des modeles simplifies de transport fiables et non fiables pour differencier telechargement, appel et telemetrie, sans reimplementer une pile TCP complete.
- [ ] **SIM-09** Definir un temps de simulation reproductible ; separer les ticks de simulation du temps mural utilise pour les timeouts de securite et l'affichage.
- [ ] **SIM-10** Utiliser un generateur pseudo-aleatoire a graine pour les scenarios et tests ; eviter les fluctuations arbitraires qui masquent les causes physiques.

## 5. P1/P2 : infrastructure physique et genie civil

- [ ] **PHY-01** Ajouter fourreaux, microfourreaux, chambres, regards, poteaux, supports de facade et chemins de cables avec occupation limitee.
- [ ] **PHY-02** Permettre cables enterres, aeriens et interieurs ; distinguer cable, support et conduite pour qu'une infrastructure puisse etre partagee.
- [ ] **PHY-03** Ajouter des cables multifibres et multipaires avec reperage, capacite, propriete et etat de chaque conducteur.
- [ ] **PHY-04** Ajouter boites d'epissures, tiroirs optiques, panneaux de brassage, jarretieres et reperage des ports.
- [ ] **PHY-05** Representer longueurs de cable, reserves et epissures ; un croisement spatial ne cree pas de connexion electrique ou optique.
- [ ] **PHY-06** Permettre pose assistee entre supports, deroulement de bobine et plans de cheminement sans exiger une action manuelle par metre.
- [ ] **PHY-07** Conserver le trajet physique exact pour les diagnostics tout en compressant les segments non ramifies pour les calculs.
- [ ] **PHY-08** Ajouter cout et duree de travaux optionnels, droits de passage, location de conduites et contraintes de capacite.
- [ ] **PHY-09** Ajouter degradations configurables : courbure excessive, epissure defectueuse, arrachement, infiltration et conducteur coupe.
- [ ] **PHY-10** Fournir des constructions de baies, armoires et locaux techniques modulaires avec interaction par facade et maintenance accessible.

**Validation :** couper un conducteur affecte uniquement les circuits qui l'utilisent ; couper un cable affecte tous ses conducteurs ; deux cables croises restent independants.

## 6. P1/P2 : FTTH et reseau optique francais

### Chaine fonctionnelle cible

```text
Services internes / interconnexion simulee
                  |
           Coeur operateur
                  |
               Collecte
                  |
             NRO avec OLT
                  |
                 PM
                  |
                 PBO
                  |
                 PTO
                  |
        ONT separe ou integre
                  |
                 Box
                  |
           Ethernet / Wi-Fi
```

Le NRO est un site accueillant des equipements, pas une machine unique. Le placement des coupleurs et l'organisation des fibres dependent du profil de reseau. Le PM est un point de mutualisation et de brassage, pas obligatoirement l'emplacement d'un coupleur.

- [ ] **FTTH-01** Implementer OLT, chassis, cartes, ports PON, repartiteur optique, PM, PBO, PTO et ONT avec roles distincts.
- [ ] **FTTH-02** Identifier les PTO, ports et circuits d'abonnes ; permettre etiquetage, recherche et suivi d'un raccordement complet.
- [ ] **FTTH-03** Implementer coupleurs et ratios de partage, pertes d'insertion, connecteurs, epissures et budget optique dans les deux sens.
- [ ] **FTTH-04** Definir longueurs d'onde, classes d'optiques, sensibilites, puissance emise et seuils de surcharge selon les profils techniques.
- [ ] **FTTH-05** Implementer GPON et XGS-PON avec capacites de ligne, overhead et debit utile distincts ; documenter les arrondis et simplifications.
- [ ] **FTTH-06** Partager la capacite par port PON et non par simple proximite de cables ; modeliser une allocation montante simplifiee et les limites descendantes.
- [ ] **FTTH-07** Ajouter compatibilite OLT/ONT, enregistrement du terminal, profil d'abonne, authentification simplifiee et etats LOS/synchronisation.
- [ ] **FTTH-08** Permettre la coexistence de technologies optiques lorsque le profil d'infrastructure et les equipements le permettent.
- [ ] **FTTH-09** Separer nombre de fibres, debit de chaque circuit et debit commercial ; supprimer l'equivalence automatique entre diametre de cable et debit individuel.
- [ ] **FTTH-10** Implementer la chaine de commande : eligibilite, reservation, intervention, brassage, raccordement, activation et recette.
- [ ] **FTTH-11** Ajouter logements raccordables, raccordes, actifs, en attente et en echec ; distinguer manque de port, saturation et defaut optique.
- [ ] **FTTH-12** Permettre mutualisation OI/OC et brassages propres aux operateurs ; le changement d'operateur suit les regles du scenario.
- [ ] **FTTH-13** Ajouter diagnostic de puissance et reflectometrie simplifiee : distance le long du trajet, evenements optiques et localisation d'une rupture.
- [ ] **FTTH-14** Ajouter en extension les architectures point a point et offres professionnelles avec ressources et garanties explicites.

**Validation :** plusieurs abonnes partagent le meme PON ; un ONT incompatible ne s'active pas ; un budget optique depasse empeche la synchronisation ; une offre ne depasse ni le PON, ni la collecte, ni les ports du terminal.

## 7. P2/P3 : cuivre, telephonie historique et migration

- [ ] **CU-01** Ajouter NRA avec DSLAM, repartiteur, sous-repartiteur, point de concentration, cable multipaire, DTI et modem.
- [ ] **CU-02** Modeliser une boucle propre a chaque abonne : longueur, section, jonctions, attenuation, bruit et etat de la paire.
- [ ] **CU-03** Implementer des profils ADSL2+ et VDSL2 avec synchronisation montante/descendante, marge au bruit et debit utile.
- [ ] **CU-04** Remplacer la formule actuelle de cuivre par des courbes documentees ; ne pas assimiler Ethernet cuivre et boucle DSL.
- [ ] **CU-05** Ajouter diaphonie agregee, perturbations, desynchronisations, repli de profil et reparations de paires.
- [ ] **CU-06** Ajouter, en mode historique, ligne analogique, alimentation de ligne, numerotation et commutation simplifiee.
- [ ] **CU-07** Ajouter interventions : test de ligne, recherche de paire, mutation, changement de port et localisation de defaut.
- [ ] **CU-08** Construire des scenarios de migration cuivre vers FTTH avec calendrier configurable, abonnes restants et fermeture par zone.

**Validation :** une ligne longue ou bruitee synchronise moins vite ; la coupure d'une paire ne coupe pas toutes les lignes ; la migration conserve l'identite et le contrat de l'abonne selon le scenario.

## 8. P1/P2/P3 : reseau mobile et radio

### Sites et secteurs

- [ ] **RAD-01** Decomposer un site en support, panneaux, secteurs, unites radio, traitement, energie et collecte.
- [ ] **RAD-02** Configurer par secteur operateur, technologie, bande, largeur de canal, puissance, gain, azimut, inclinaison et diagramme d'antenne.
- [ ] **RAD-03** Distinguer site, cellule et porteuse ; attribuer des identifiants stables et autoriser plusieurs operateurs sur un meme support.
- [ ] **RAD-04** Implementer bandes compatibles avec les terminaux, largeurs de canal, MIMO et profils de modulation/codage simplifies.
- [ ] **RAD-05** Representer le spectre comme une ressource : deux technologies ou operateurs ne disposent pas gratuitement de la meme largeur de bande.

### Propagation et partage radio

- [ ] **RAD-06** Definir l'echelle bloc/metre et le modele de propagation par environnement ; documenter calibration et domaine de validite.
- [ ] **RAD-07** Modeliser bilan de liaison avec EIRP, pertes et gain de reception ; calculer les contraintes montantes du terminal et pas uniquement le signal descendant.
- [ ] **RAD-08** Distinguer exterieur, interieur, relief, vegetation et materiaux ; utiliser des profils de pertes configurables et testables.
- [ ] **RAD-09** Ajouter bruit et interferences co-canal pour estimer SINR et qualite ; ne pas confondre puissance recue et debit disponible.
- [ ] **RAD-10** Exposer les indicateurs adaptes a la technologie, notamment RSRP, RSRQ et SINR pour LTE/NR, sans reutiliser un unique chiffre comme toutes les mesures.
- [ ] **RAD-11** Partager les ressources de cellule entre abonnes ; inclure largeur de bande, qualite radio, MIMO, overhead et plafond de collecte.
- [ ] **RAD-12** Differencier FDD et TDD avec repartition montante/descendante ; ne pas attribuer arbitrairement un pourcentage fixe d'upload a toute une generation.
- [ ] **RAD-13** Agreger uniquement les porteuses autorisees par la configuration reseau et le terminal ; comptabiliser les ressources sur leurs cellules reelles.
- [ ] **RAD-14** Modeliser la double connectivite de maniere explicite ; ne pas transformer toutes les antennes visibles en une seule reserve de debit.
- [ ] **RAD-15** Ajouter en mode expert diffraction approximative, masques de relief, fading statistique et effets meteorologiques pertinents, notamment sur les hautes frequences.

### Mobilite et coeur mobile

- [ ] **MOB-01** Introduire SIM/eSIM, abonnement, identite mobile, authentification simplifiee et association d'une session a un terminal.
- [ ] **MOB-02** Separer signal present, cellule accessible, enregistrement, session de donnees et disponibilite d'un service.
- [ ] **MOB-03** Implementer cellule de service, voisines, reselection, handover, hysteresis et temporisations pour eviter les basculements permanents.
- [ ] **MOB-04** Choisir la cellule selon qualite, charge, compatibilite et politique ; ne pas favoriser systematiquement une mauvaise 5G face a une bonne 4G.
- [ ] **MOB-05** Maintenir ou interrompre explicitement les sessions lors d'un deplacement ; traiter les trous de couverture et les echecs de handover.
- [ ] **MOB-06** Representer un coeur mobile simplifie avec fonctions de gestion des abonnes, mobilite et acheminement des donnees, sans exiger un bloc par fonction normalisee.
- [ ] **MOB-07** Distinguer 5G NSA avec ancre LTE et 5G SA avec coeur approprie ; rendre visibles leurs dependances.
- [ ] **MOB-08** Ajouter itinerance, selection manuelle, MVNO et accords inter-operateurs avec droits d'acces explicites.
- [ ] **MOB-09** Implementer SMS, appels, VoLTE puis VoNR avec signalisation simplifiee et comportement en cas de perte de service.
- [ ] **MOB-10** Ajouter double SIM, mode avion, bandes supportees, capacites du modem et limites des terminaux.
- [ ] **MOB-11** Ajouter Small Cells, couverture interieure et reseaux mobiles prives en extension.

**Validation :** ajouter des utilisateurs diminue la ressource disponible ; ajouter une antenne co-canal peut augmenter les interferences ; une perte de collecte n'efface pas artificiellement le signal ; un deplacement suit une sequence d'attachement reproductible.

## 9. P1/P2 : reseaux domestiques, entreprises et IP

- [ ] **LAN-01** Distinguer box, routeur, switch, point d'acces, repeteur et pare-feu ; implementer leurs roles plutot que de simples niveaux de debit.
- [ ] **LAN-02** Ajouter ports WAN/LAN, vitesses negociees, liens full-duplex, limite de commutation et etat du lien.
- [ ] **LAN-03** Implementer tables MAC simplifiees, domaines de diffusion et prevention des boucles ; un switch n'est pas un routeur IP.
- [ ] **LAN-04** Ajouter Wi-Fi 2,4/5/6 GHz avec profils de canaux autorises, largeur, interference, contention et attenuation.
- [ ] **LAN-05** Ajouter SSID, authentification, reseau invite et isolation ; ne pas simuler une securite reelle en stockant des secrets dans les donnees publiques.
- [ ] **LAN-06** Ajouter mesh et roaming ; la liaison de retour d'un repeteur consomme elle aussi une ressource et peut limiter le debit.
- [ ] **IP-01** Implementer IPAM, sous-reseaux, reservations, baux DHCP, IPv4 et IPv6 ; distinguer adresses de gestion, WAN et LAN.
- [ ] **IP-02** Implementer passerelles, DNS, routes connectees et statiques ; distinguer panne DNS et absence de connectivite IP.
- [ ] **IP-03** Ajouter NAT, redirections, CGNAT optionnel, pare-feu et regles de filtrage avec limites de tables.
- [ ] **IP-04** Ajouter VLAN, trunks et separation des services Internet, telephonie, gestion et reseaux prives.
- [ ] **IP-05** Ajouter VPN et tunnels avec overhead, MTU et dependance a un chemin sous-jacent ; eviter les liens virtuels de capacite infinie.
- [ ] **IP-06** Ajouter outils ping, traceroute, resolution DNS, inspection de routes et diagnostic de port avec resultats issus du moteur.

**Validation :** un terminal sans DHCP peut perdre son acces alors que la fibre fonctionne ; un lien Ethernet limite le debit final ; un reseau invite ne rejoint pas un VLAN prive sans regle explicite.

## 10. P2/P3 : collecte, transport et interconnexion

- [ ] **CORE-01** Distinguer services heberges, coeur operateur et interconnexion Internet simulee ; un bloc serveur n'est pas automatiquement tout Internet.
- [ ] **CORE-02** Ajouter PoP, routeurs de collecte, liens metropolitains, anneaux et liens longue distance.
- [ ] **CORE-03** Implementer metriques, selection de chemin ponderee, routes de secours et convergence ; ne plus utiliser seulement le nombre de sauts.
- [ ] **CORE-04** Distinguer routes administratives, politique et optimisation ; eviter de rerouter toutes les sessions a chaque fluctuation de charge.
- [ ] **CORE-05** Ajouter aggregation de liens et ECMP avec repartition documentee ; une session unique ne beneficie pas automatiquement de toute la capacite agregee.
- [ ] **CORE-06** Ajouter optiques, portees, longueurs d'onde, incompatibilites et transport CWDM/DWDM en mode expert.
- [ ] **CORE-07** Ajouter faisceaux hertziens point a point avec alignement, visibilite, zone de Fresnel approximative et collecte limitee.
- [ ] **CORE-08** Ajouter OSPF ou IS-IS simplifie, puis BGP, AS, peering, transit et points d'echange avec politiques configurables.
- [ ] **CORE-09** Ajouter offres de fibres noires, circuits loues, Ethernet operateur et VPN/MPLS en extension.
- [ ] **CORE-10** Ajouter satellite en extension independante : segment sol, couverture, capacite partagee, latence et eventuels basculements, sans trafic reel vers Internet.

**Validation :** un lien rompu declenche une convergence bornee ; la redondance exige des trajets physiques reellement distincts ; une politique BGP peut choisir autre chose que le chemin geographiquement le plus court.

## 11. P1/P2 : energie et environnement des equipements

- [ ] **PWR-01** Distinguer equipements actifs et passifs ; PM/PBO/coupleurs passifs n'ont pas de consommation necessaire au transport optique.
- [ ] **PWR-02** Ajouter consommation au repos et en charge, alimentations, distribution electrique, onduleurs et batteries.
- [ ] **PWR-03** Calculer autonomie avec unites coherentes de puissance et d'energie ; gerer recharge, vieillissement optionnel et priorites de delestage.
- [ ] **PWR-04** Ajouter groupes electrogenes, carburant, delai de demarrage et basculement ; permettre des essais de secours.
- [ ] **PWR-05** Ajouter temperature, refroidissement et seuils de reduction de puissance/arret avec un modele agrege par baie ou local.
- [ ] **PWR-06** Modeliser demarrage, synchronisation et retour en service ; eviter un retablissement instantane de toutes les fonctions.
- [ ] **PWR-07** Integrer Forge Energy de maniere optionnelle et conserver un fonctionnement autonome ; definir precisement la conversion avec les unites du mod.

## 12. P1/P2 : services utiles et demande de trafic

### Services internes au monde

- [ ] **SVC-01** Implementer SMS entre abonnes avec contacts, numerotation, accuse de reception et file d'attente bornee hors couverture.
- [ ] **SVC-02** Ajouter appels, groupes et messagerie vocale ; integrer eventuellement un mod vocal via une dependance optionnelle.
- [ ] **SVC-03** Ajouter sites et applications internes heberges sur les serveurs du monde : annuaire, portail operateur, intranet et messagerie.
- [ ] **SVC-04** Ajouter telechargements et contenus virtuels limites en taille ; rendre visibles debit, delai, interruption et reprise.
- [ ] **SVC-05** Ajouter usages de streaming simules avec tampon, appels sensibles a la gigue et sauvegardes sensibles au debit montant.
- [ ] **SVC-06** Ajouter telemetrie, capteurs, alarmes, affichages et commandes redstone distantes avec autorisations et modes degrades.
- [ ] **SVC-07** Ajouter serveurs avec capacites CPU/stockage/services abstraites ; distinguer saturation applicative et saturation reseau.
- [ ] **SVC-08** Ne pas introduire de navigateur arbitraire, proxy Internet, execution de scripts non fiables ou acces aux fichiers de l'hote ; isoler strictement les contenus simules.

### Abonnes non joueurs et charge

- [ ] **LOAD-01** Introduire logements, entreprises et etablissements comme abonnes logiques sans creer une entite Minecraft permanente pour chacun.
- [ ] **LOAD-02** Definir profils de demande residentiels, bureaux, industries, ecoles, gares et zones touristiques.
- [ ] **LOAD-03** Ajouter heures de pointe, variations jour/nuit, evenements et croissance de la demande avec graines reproductibles.
- [ ] **LOAD-04** Generer des flux selon les usages, pas uniquement des speedtests aleatoires ; separer demande offerte et trafic effectivement transporte.
- [ ] **LOAD-05** Agreger les populations hors zone active sans supprimer leur charge sur les goulots partages.

**Validation :** une panne interrompt le service dependant du reseau ; un appel peut etre degrade par la gigue sans epuiser la bande passante ; les usages des abonnes persistent logiquement hors chunks charges.

## 13. P2 : operateurs, economie et cadre francais

- [ ] **FR-01** Separer operateur d'infrastructure, operateur commercial, operateur mobile, MVNO, hebergeur, collectivite et sous-traitant.
- [ ] **FR-02** Ajouter operateurs fictifs personnalisables, marques et couleurs ; ne pas rendre le fonctionnement dependant de marques reelles.
- [ ] **FR-03** Ajouter zones de deploiement, logements, adresses, eligibilite et referentiels de sites ; les identifiants d'exploitation restent distincts des adresses IP.
- [ ] **FR-04** Modeliser mutualisation, location, cofinancement simplifie, RIP et appels d'offres via des mecanismes ludiques explicitement documentes.
- [ ] **FR-05** Ajouter offres commerciales, debit plafond, quotas, activation, suspension, resiliation et portabilite simulee.
- [ ] **FR-06** Separer debit maximal, debit minimum garanti lorsqu'il existe, disponibilite contractuelle et garantie de retablissement ; ne pas promettre les memes garanties a tous les abonnements.
- [ ] **FR-07** Ajouter CAPEX/OPEX, energie, location, maintenance, revenus et interventions ; rendre l'economie desactivable sans changer la physique du reseau.
- [ ] **FR-08** Ajouter satisfaction, reclamations, churn, concurrence et cooperation avec regles configurables et sans dependance a des donnees personnelles reelles.
- [ ] **FR-09** Ajouter plans de numerotation fictifs coherents avec les profils choisis ; tout appel d'urgence reste une simulation sans connexion aux services reels.
- [ ] **FR-10** Creer des profils d'epoque pour cuivre, generations mobiles et migration ; documenter les choix par territoire et operateur lorsque necessaire.
- [ ] **FR-11** Documenter les bandes, largeurs et usages a partir de sources officielles datees ; ne pas presenter une bande experimentale ou specialisee comme universellement deployee.
- [ ] **FR-12** Ajouter regles d'autorisation et de partage du spectre en mode gestion, avec distinction entre simplification de gameplay et reglementation reelle.

### References a utiliser lors de l'implementation

| Source | Usage |
| --- | --- |
| ARCEP, `https://www.arcep.fr/` | Architecture du marche, fibre, mutualisation, numerotation, couverture et calendriers publies. |
| ANFR, `https://www.anfr.fr/` | Spectre, sites radio et cadre des frequences. |
| 3GPP, `https://www.3gpp.org/` | Terminologie et comportements LTE/NR, mobilite et coeur mobile. |
| ITU-T, `https://www.itu.int/` | Technologies PON et transmission optique. |
| IETF, `https://www.ietf.org/` | Protocoles IP, routage et services associes. |
| IEEE, `https://www.ieee.org/` | Ethernet et Wi-Fi. |

Ces liens sont des points d'entree, pas une verification de l'etat reglementaire actuel. Chaque profil technique devra citer la publication, sa version, sa date et les simplifications retenues. Aucun calendrier national ou usage de bande ne doit etre fige sur la seule base de ce document.

## 14. P1/P2 : diagnostic, incidents et supervision

- [ ] **OPS-01** Ajouter testeur optique, reflectometre, testeur cuivre, analyseur Wi-Fi/radio et inspection IP, tous alimentes par les memes donnees que le moteur.
- [ ] **OPS-02** Permettre de suivre un service de bout en bout : terminal, acces, collecte, coeur et serveur ; afficher le goulot et la cause de refus.
- [ ] **OPS-03** Ajouter incidents configurables : travaux, defaut de brassage, carte en panne, coupure electrique, surchauffe, brouillage et mauvaise configuration.
- [ ] **OPS-04** Relier chaque incident a une cause observable, un perimetre d'impact et une reparation ; eviter les pannes aleatoires indiagnostiquables.
- [ ] **OPS-05** Ajouter alarmes avec severite, acquittement, horodatage, cause racine et correlation pour eviter une alarme par abonne lors d'une seule coupure amont.
- [ ] **OPS-06** Ajouter tickets, affectation, maintenance planifiee, interventions, compte rendu et recette apres reparation.
- [ ] **OPS-07** Mesurer disponibilite, latence, pertes, gigue, debit, utilisation, demande refusee et abonnes affectes.
- [ ] **OPS-08** Enregistrer mesures radio datees par operateur, technologie et position ; distinguer aucune mesure, aucun signal et mesure perimee.
- [ ] **OPS-09** Distinguer carte predictive et releves reels ; indiquer precision, hauteur de mesure, age et hypotheses de prediction.
- [ ] **OPS-10** Ajouter inventaire et recherche par nom, port, circuit, PTO, abonne et adresse ; proposer cartes physique, logique et commerciale.
- [ ] **OPS-11** Afficher les chemins physiques et pas seulement des lignes droites entre noeuds ; filtrer par dimension, couche et operateur autorise.
- [ ] **OPS-12** Conserver un journal d'audit des modifications avec auteur, avant/apres et resultat, sans secrets ni contenu prive des communications.
- [ ] **OPS-13** Ajouter export de rapports et API versionnee en lecture, avec pagination, filtrage et autorisations.

## 15. P1/P2 : ergonomie, progression et integrations

- [ ] **UX-01** Fournir trois profils : decouverte avec assistance, simulation operateur, expert ; conserver les memes invariants de connectivite et de capacite.
- [ ] **UX-02** Ajouter tutoriel et guide integre : premier raccordement, premier service, premiere panne et premiere reparation.
- [ ] **UX-03** Ajouter recettes, butin, outils requis, progression et compatibilite JEI/EMI optionnelle ; ne pas oublier les blocs hors routeurs.
- [ ] **UX-04** Utiliser traductions pour tous les textes ; definir terminologie francaise et anglaise coherente et unites lisibles.
- [ ] **UX-05** Expliquer les erreurs en termes metier : fibre libre manquante, ONT inconnu, puissance insuffisante, pas de route, service arrete.
- [ ] **UX-06** Ajouter etiquettes, favoris, duplication de configurations et pose en serie avec validation des droits et des ressources.
- [ ] **UX-07** Concevoir les ecrans pour plusieurs resolutions et echelles GUI ; rendre le dashboard utilisable sur desktop et mobile avec commandes tactiles.
- [ ] **UX-08** Ne pas utiliser uniquement la couleur pour les etats ; ajouter libelles, icones et navigation accessible.
- [ ] **UX-09** Introduire entreprises et roles multijoueurs : technicien, ingenieur, exploitant et administrateur, avec permissions minimales.
- [ ] **UX-10** Ajouter une API d'integration documentee pour energie, voix, cartes et automatismes ; gerer absence ou changement de version des mods optionnels.
- [ ] **UX-11** Fournir scenarios : village rural, quartier dense, festival, reseau d'entreprise, panne majeure et migration historique.

## 16. P0/P1 : optimisation structurelle

L'optimisation doit commencer par des mesures. Les structures ci-dessous sont des orientations a valider au profilage, pas une justification pour ajouter toutes les abstractions avant le premier usage.

### Topologie et routes

- [ ] **PERF-01** Mesurer temps CPU, allocations, memoire, I/O et trafic de synchronisation avant et apres chaque changement majeur.
- [ ] **PERF-02** Compresser les suites de cables sans branchement en segments ; conserver des index spatiaux pour retrouver une rupture au bon emplacement.
- [ ] **PERF-03** Maintenir listes d'adjacence et index noeud/port/circuit/cable ; eviter le parcours de toutes les aretes pour chaque recherche.
- [ ] **PERF-04** Regrouper les modifications d'une meme rafale de construction ; recalculer les composants affectes selon un budget par tick.
- [ ] **PERF-05** Eviter la copie de chemins complets a chaque etape de parcours ; reconstruire les chemins a partir de parents ou de references partagees.
- [ ] **PERF-06** Mettre en cache routes et dependances avec revisions ; invalider localement et borner la taille par une politique d'eviction explicite.
- [ ] **PERF-07** Reutiliser les calculs communs de routes vers les memes destinations ; ne pas rechercher tous les serveurs pour chaque session a chaque tick.
- [ ] **PERF-08** Evaluer la suppression des BlockEntities inutiles sur les cables passifs ; conserver uniquement l'etat necessaire a la construction et a la persistance.

### Radio et niveaux de detail

- [ ] **PERF-09** Indexer spatialement les cellules et limiter les candidates par une borne conservatrice de portee ; ne pas scanner toutes les antennes du monde pour chaque joueur.
- [ ] **PERF-10** Recalculer l'attachement selon deplacement, changement radio ou temporisation ; eviter un raycast complet si rien de pertinent n'a change.
- [ ] **PERF-11** Mettre en cache relief, obstacles et resultats par zone, hauteur, bande et configuration ; invalider les zones concernees par les constructions.
- [ ] **PERF-12** Utiliser un premier filtre de propagation peu couteux puis affiner les meilleures candidates et les interferences dominantes.
- [ ] **PERF-13** Definir des niveaux de detail : precis pres des joueurs, agrege dans les zones distantes, statistiques pour les populations massives.
- [ ] **PERF-14** Verifier qu'un changement de niveau de detail conserve les ressources, la charge partagee et les etats ; ne pas offrir du debit gratuit hors ecran.
- [ ] **PERF-15** Calculer les cartes predictives en travaux limites, annulables et prioritaires selon la zone visible ; ne pas bloquer un tick pour toute une carte.

### Simulation et concurrence

- [ ] **PERF-16** Separer frequences de mise a jour : trafic actif, controle radio, statistiques et economie n'ont pas besoin de tourner tous a 20 Hz.
- [ ] **PERF-17** Utiliser des pas ou evenements adaptes avec rattrapage borne ; une baisse de TPS ne doit pas declencher une spirale de recalculs.
- [ ] **PERF-18** Maintenir les allocations par ressource au lieu de reparcourir tous les blocs de tous les chemins pour chaque flux.
- [ ] **PERF-19** Reduire allocations temporaires, conversions de chaines et recherches lineaires dans les boucles chaudes, uniquement apres profilage.
- [ ] **PERF-20** Reserver le thread Minecraft aux acces au monde et commits d'etat ; les workers calculent sur des instantanes immuables et revisions identifiees.
- [ ] **PERF-21** Rejeter ou recalculer les resultats devenus obsoletes ; borner executors, files, travail en vol et cout de publication sur le thread serveur.
- [ ] **PERF-22** Garder les operations locales interactives prioritaires sur les cartes, historiques et predictions ; ne pas degrader les droits ou l'integrite sous surcharge.

### Chunks, sauvegardes et caches

- [ ] **PERF-23** Maintenir un graphe logique persistant independant des chunks charges ; aucune simulation courante ne force le chargement de tout le reseau.
- [ ] **PERF-24** Definir la politique des evenements hors chunks charges : absence de changement physique non observe, energie abstraite et rapprochement au rechargement.
- [ ] **PERF-25** Sauvegarder seulement les donnees modifiees selon les mecanismes supportes ; mesurer et repartir les couts de serialization.
- [ ] **PERF-26** Fixer une limite de memoire et une eviction pour chaque cache : routes, tuiles, propagation, mesures, contenus et instantanes.
- [ ] **PERF-27** Nettoyer sessions terminees, observateurs deconnectes et travaux annules ; verifier l'absence de references a d'anciens mondes.

### Synchronisation et dashboard

- [ ] **PERF-28** Envoyer uniquement les donnees utiles aux joueurs ou ecrans abonnes ; appliquer cadence limitee, deltas et numero de revision.
- [ ] **PERF-29** Borner taille des paquets, pagination et cout de decodage ; gerer reconnexion, perte de revision et resynchronisation complete.
- [ ] **PERF-30** Ajouter filtrage spatial et par permissions a l'API ; ne pas renvoyer tout le reseau et tous les historiques a chaque rafraichissement.
- [ ] **PERF-31** Choisir entre polling conditionnel, SSE ou WebSocket selon les mesures ; prevoir backpressure et deconnexion des clients trop lents.
- [ ] **PERF-32** Rendre les cartes multi-resolution avec culling, regroupement et tuiles mises en cache ; borner le dezoom et les requetes simultanees.
- [ ] **PERF-33** Mettre a jour les composants modifies du dashboard sans reconstruire le formulaire selectionne a chaque rafraichissement.
- [ ] **PERF-34** Eviter le rendu et les animations permanentes lorsque la page est masquee ou inactive ; limiter les allocations par image.
- [ ] **PERF-35** Invalider les tuiles de terrain localement et limiter leur retention sur client et serveur.

## 17. Mesures et budgets de performance

### Protocole de mesure obligatoire

- [ ] **BENCH-01** Creer des mondes et generateurs de topologies reproductibles, avec graine et manifeste du scenario.
- [ ] **BENCH-02** Enregistrer CPU, RAM, JVM, versions, arguments, distance de simulation et liste de mods ; comparer sur la meme machine de reference.
- [ ] **BENCH-03** Mesurer apres echauffement, sur plusieurs executions, avec mediane, p95, p99 et maximum ; comparer aussi un serveur temoin sans reseau telecom.
- [ ] **BENCH-04** Mesurer le cout incremental du mod, pas seulement le TPS global ; distinguer calcul serveur, workers, sauvegarde et navigateur.
- [ ] **BENCH-05** Instrumenter nombre de noeuds, circuits, cellules, flux, candidats radio, chemins recalcules, tailles de files et taux de hit des caches.
- [ ] **BENCH-06** Exporter des compteurs de diagnostic et profils JFR ou equivalent ; echantillonner les traces pour ne pas creer une surcharge de logs.

### Scenarios initiaux proposes

Ces tailles sont des charges de test a construire, pas des capacites deja atteintes. Un equipement logique, un abonne et un bloc de cable sont comptes separement.

| Profil | Equipements logiques | Abonnes logiques | Flux actifs | Cellules | Joueurs connectes | Clients web |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Quartier | 200 | 500 | 200 | 12 | 5 | 1 |
| Ville | 2 000 | 10 000 | 2 000 | 120 | 20 | 3 |
| Stress | 10 000 | 100 000 | 10 000 | 600 | 50 | 10 |

- [ ] **BENCH-07** Ajouter a chaque profil des segments longs, embranchements, boucles, goulots partages et coordonnees negatives ; publier le nombre de blocs physiques et la longueur des circuits.
- [ ] **BENCH-08** Tester stabilite sans modification, construction en rafale, suppression d'une artere, handovers simultanes et afflux d'abonnes.
- [ ] **BENCH-09** Tester dechargement/rechargement, sauvegarde, redemarrage, exploration de carte, clients lents et requetes invalides.
- [ ] **BENCH-10** Effectuer un test longue duree d'au moins deux heures apres echauffement et verifier que memoire et files atteignent un plateau a charge constante.

### Budgets provisoires a confirmer au premier profilage

| Indicateur | Objectif de conception |
| --- | --- |
| Tick serveur complet | Viser 20 TPS, soit un budget total de 50 ms, sans attribuer tout ce budget au mod. |
| Cout incremental telecom, profil Ville stabilise | Viser p95 <= 2 ms et p99 <= 5 ms par tick sur la machine de reference. |
| Recalcul structurel | Travail fractionne dans le budget telecom ; aucun scan global monolithique apres chaque pose. |
| Prise en compte d'une modification locale | Viser <= 1 seconde en charge nominale ; afficher un etat de recalcul si le travail est differe. |
| Supervision courante | Rafraichissement cible de 1 a 2 secondes ; mesures fines sur abonnement explicite. |
| API de consultation sur instantane | Viser p95 <= 250 ms hors generation de rapports et travaux lourds. |
| Sauvegarde et migration | Mesurer duree et pics de tick ; definir un seuil de regression apres la mesure initiale. |
| Memoire | Caches et historiques bornes ; plafond chiffre a fixer a partir du profil Ville et du budget JVM retenu. |
| Surcharge | Files bornees, travaux secondaires differes ou refuses et etat visible, jamais croissance illimitee. |

Ces objectifs ne sont pas des garanties universelles. Si le profil Stress les depasse, conserver l'integrite et les limites memoire, mesurer la degradation et ajuster les niveaux de detail. Tout changement de seuil doit etre justifie et versionne ; il ne doit pas masquer une regression.

## 18. Tests de correction et de realisme

### Invariants automatises

- [ ] **TEST-01** Tester conservation des capacites sur chaque ressource, equite, priorites, sens du trafic et absence de debit negatif.
- [ ] **TEST-02** Tester l'absence de collision d'identites et d'adresses apres recalcul, migration, redemarrage et changement de dimension.
- [ ] **TEST-03** Tester continuite fibre/paire, brassage, budget optique, compatibilite des ports et limites commerciales.
- [ ] **TEST-04** Tester routes, absence de chemin, boucles, chemins de secours, ECMP et convergence bornee.
- [ ] **TEST-05** Tester propagation sur geometries simples, ordre des pertes par materiau, azimut, inclinaison et limites montantes.
- [ ] **TEST-06** Tester contention radio, agregation autorisee, interference, NSA/SA et handover sans creation de capacite artificielle.
- [ ] **TEST-07** Tester DHCP, DNS, VLAN, NAT, pare-feu et isolation des abonnes.
- [ ] **TEST-08** Tester bilan energetique, autonomie, coupure, delestage et redemarrage des services.
- [ ] **TEST-09** Tester avec graines fixes et tolerances documentees ; comparer les approximations a des cas de reference simples plutot qu'a une promesse de fidelite absolue.

### Integration et securite

- [ ] **TEST-10** Ajouter GameTests pour pose, destruction, explosion, chunks et persistance ; completer avec des tests de serveur dedie.
- [ ] **TEST-11** Tester les migrations sur des sauvegardes representatives de chaque version supportee.
- [ ] **TEST-12** Tester paquets falsifies, droits insuffisants, durees extremes, valeurs invalides, corps trop gros et rafales de requetes.
- [ ] **TEST-13** Tester HTTP et client Minecraft simultanement pendant modification, sauvegarde et arret du monde.
- [ ] **TEST-14** Tester limites memoire, clients web lents, reconnexion et annulation sans file infinie ni fuite de thread.
- [ ] **TEST-15** Ajouter tests de contrat API et tests frontend pour precision des identifiants, formulaires, cartes et etats d'erreur.

### Scenarios d'acceptation metier

| Scenario | Resultat attendu |
| --- | --- |
| Deux abonnes sur un PON sature | Partage coherent, somme bornee, indicateurs identiques sur outils et web. |
| Fibre coupee en milieu de trajet | Abonnes concernes identifies ; distance de defaut correcte ; aucune panne sur un circuit independant. |
| Box mal configuree | Fibre synchronisee mais service IP indisponible, avec diagnostic distinct. |
| Mobile recevant un site sans collecte | Signal visible ; service de donnees absent ou degrade selon redondance. |
| Deplacement entre deux cellules | Handover selon seuils et temporisations, pas d'oscillation permanente. |
| Coupure electrique d'un site | Secours puis epuisement selon autonomie ; retour progressif apres retablissement. |
| Fermeture d'un dashboard | Suppression des abonnements de mesures et du travail qui n'a plus de consommateur. |
| Dechargement des chunks reseau | Pas de suppression artificielle de la topologie ou des charges logiques. |
| Changement d'operateur | Droits, contrat et brassage coherents, sans acces indu aux ressources d'un tiers. |

## 19. Construction, documentation et maintenance

- [x] **REL-01** Remplacer le README du template par installation, versions supportees, configuration, demarrage et premier reseau fonctionnel.
- [x] **REL-02** Rendre la construction du dashboard reproductible depuis ses sources : manifeste, dependances verrouillees si necessaires et integration au build du mod.
- [ ] **REL-03** Verifier que les assets embarques correspondent aux sources et qu'aucun ancien bundle ou fichier de sauvegarde inutile n'est livre.
- [ ] **REL-04** Ajouter CI pour compilation, tests unitaires, integration pertinente, frontend et verification des ressources.
- [ ] **REL-05** Ajouter controles de traduction, recettes, butin, modeles et textures ; generer les ressources repetitives lorsque cela reduit les erreurs.
- [ ] **REL-06** Publier schema de configuration, schema API, limites de simulation et guide d'administration securisee.
- [ ] **REL-07** Documenter chaque approximation physique, echelle et profil France avec source datee ; distinguer valeurs normatives et equilibrage de jeu.
- [ ] **REL-08** Ajouter changelog, politique de migration, sauvegardes conseillees et procedure de diagnostic sans demander de secrets aux utilisateurs.
- [ ] **REL-09** Verifier licences des textures, bibliotheques, sons, marques et donnees externes avant distribution.
- [ ] **REL-10** Conserver une matrice de compatibilite Minecraft/NeoForge/mods optionnels et ne pas annoncer une version non testee.

## 20. Ordre de livraison et dependances

| Jalon | Dependances | Livrable jouable | Condition de sortie |
| --- | --- | --- | --- |
| J0 : base fiable | Aucune | Version actuelle securisee, compilable et instrumentee. | Tests de cycle de vie et securite passes ; mesures initiales publiees. |
| J1 : noyau physique et logique | J0 | Ports, circuits, identites, ressources partagees et persistance versionnee. | Invariants de capacite et redemarrage passes ; pas de scan global par action. |
| J2 : quartier FTTH | J1 | OLT, PM, PBO, PTO, ONT, box, offre et raccordement complet. | Un abonne active un service puis une rupture est localisee et reparee. |
| J3 : usages et reseau local | J2 | DHCP/DNS, Ethernet/Wi-Fi, services internes et trafic d'abonnes. | Les pannes de chaque couche produisent des effets et diagnostics distincts. |
| J4 : reseau mobile coherent | J1 + services de J3 | Secteurs, SIM, collecte, partage radio, mobilite et SMS. | Charge, perte de collecte et handover valides sur scenario reproductible. |
| J5 : exploitation operateur | J2 + J3 + J4 | Energie, contrats, roles, alarmes, tickets et supervision. | Scenario multijoueur de deploiement et maintenance sans droits excessifs. |
| J6 : ville et transport | J5 | Collecte redondante, interconnexion, populations agregees et optimisation. | Profil Ville mesure, budgets confirmes et test longue duree valide. |
| J7 : extensions expertes | Selon module | Cuivre historique, optique avancee, routage expert, satellite et reseaux prives. | Chaque module est documente, testable et desactivable sans casser le noyau. |

Les travaux de performance, de securite et de tests accompagnent tous les jalons. Ils ne sont pas reportes a J6. Les corrections du mobile existant appartiennent a J0 ; son enrichissement appartient a J4.

### Premiere tranche recommandee

1. Obtenir un build et un demarrage dedie reproductibles.
2. Securiser les paquets, l'API web et les acces concurrents.
3. Corriger identites, adressage et sauvegardes.
4. Mesurer le moteur et definir ses budgets sur une machine de reference.
5. Introduire ports, circuits et allocation de capacite partagee.
6. Livrer un raccordement FTTH complet avec un service utile.
7. Ajouter une panne, un outil de localisation et une reparation verifiable.

## 21. Definition de termine

Une tache ne peut etre cochee que lorsque les conditions applicables sont remplies :

- [ ] Le comportement metier est implemente et relie au moteur, pas seulement affiche dans une interface.
- [ ] Le serveur valide les entrees et les permissions ; les clients ne peuvent pas imposer un resultat.
- [ ] Les cas normaux, degradations, pannes et reprises sont testes.
- [ ] La persistance et la migration sont definies pour les donnees introduites.
- [ ] Les couts CPU, memoire, reseau et disque sont mesures sur un scenario pertinent.
- [ ] Les collections, caches, files et historiques ont des limites explicites.
- [ ] Le fonctionnement hors chunks charges et entre dimensions est defini.
- [ ] Les interfaces expliquent l'etat et ses causes, avec traductions et unites coherentes.
- [ ] Les simplifications et sources techniques sont documentees.
- [ ] La fonctionnalite est verifiee en solo et sur serveur dedie lorsqu'elle concerne les deux.

## 22. Limites volontaires

Pour conserver un mod realiste mais exploitable, ne pas rechercher par defaut :

- Une simulation electromagnetique exacte de chaque bloc et de chaque trajet multiple.
- Une emulation complete de chaque protocole telecom et de chaque paquet utilisateur.
- Une entite Minecraft active pour chaque abonne, fibre ou terminal virtuel.
- Le chargement permanent de tous les chunks traverses par une infrastructure.
- Des appels, SMS, comptes operateurs ou acces Internet reels.
- Une reproduction juridique exhaustive de la reglementation francaise.
- Une complexite experte obligatoire pour construire le premier reseau.

La cible est un simulateur causal et coherent : les infrastructures limitent les circuits, les circuits limitent les services, les abonnes partagent des ressources, et les outils permettent de comprendre puis de corriger les problemes. Le niveau de detail doit augmenter la qualite des decisions du joueur sans compromettre la stabilite du monde.
