# Telecom Mod : construire et utiliser son reseau

Mise a jour : 18 septembre 2026.

## 1. Direction du projet

Construire un reseau de telecommunications inspire de la France, raccorder des equipements et l'utiliser dans Minecraft. Le realisme doit se voir dans les connexions, la couverture, les debits et les communications, sans transformer le mod en logiciel de gestion.

**Hors perimetre :** electricite, batteries, carburant, refroidissement, tickets, interventions administratives, facturation, contrats, gestion d'entreprise et pannes aleatoires imposees. Une rupture de cable ou une mauvaise configuration continue naturellement d'affecter le reseau.

Cette version remplace l'ancienne roadmap detaillee. Les cases ouvertes sont des fonctionnalites a faire, pas des fonctionnalites deja disponibles.

## 2. Base deja en place

- [x] Migration du code et des ressources d'items vers Minecraft 1.21.11 / NeoForge 21.11.42.
- [x] Sauvegarde du graphe adaptee et protection contre le remplacement d'un fichier reseau illisible.
- [x] Premier durcissement des paquets et du dashboard : controles serveur, validation des hotes/origines et limites de requetes. Le dashboard fonctionne sans authentification ; son acces reseau doit etre restreint.
- [x] IP mobiles persistantes par joueur ; agregation limitee a l'antenne de service.
- [x] Premiers caches bornes et suppression des recalculs inutiles lors de la restauration des blocs connus.
- [x] Build du dashboard reproductible, documentation de lancement et suites de tests ajoutees.

Derniere validation du lot initial : 48 tests JUnit, 4 tests dashboard et 4 GameTests Telecom reussis, plus un test vanilla. La recette visuelle, le multijoueur interactif et les performances sur de grands reseaux restent a verifier.

**Lot couverture web :** moteur radio partage, grille coloree, filtres, hauteur de reception, etats inconnus et calcul progressif implementes. Tests de mur pose/retire, commandes strictes, limites de file, cache et coherence du moteur ajoutes. La carte en jeu et les faisceaux hertziens ne font pas partie de ce lot.

## 3. Ordre de realisation

La couverture calculee, les IP fixes stables et la coherence des debits filaires constituent le socle deja implemente. L'ordre des prochains lots est le suivant :

| Priorite | Objectif | Resultat attendu |
| --- | --- | --- |
| 1 | Antennes configurables et partage radio | Regler secteurs, orientation, inclinaison, puissance et largeur de bande ; repercuter ces reglages sur le smartphone et les cartes, partager la capacite radio et stabiliser le passage entre cellules. |
| 2 | Faisceaux hertziens entre sites | Relier des sites par des paraboles directionnelles, avec alignement, obstacles, zone de Fresnel simplifiee, capacite partagee, latence, relais et affichage sur la carte. |
| 3 | Optimisation des reseaux monumentaux | Remplacer les recalculs globaux a chaque modification par des mises a jour locales, regroupees et progressives, sans chargement force de chunks ni interruption du trafic non concerne. |
| 4 | Ports, brassage et fibres independantes | Identifier les ports et choisir les raccordements ; transporter plusieurs fibres independantes sans connexion automatique aux croisements. |
| 5 | Chaine FTTH complete | Raccorder une habitation par NRO/OLT -> PM -> PBO -> PTO -> ONT -> box, avec GPON/XGS-PON, partage de capacite et pertes optiques. |

Les lots antennes et faisceaux hertziens s'appuient sur le graphe et les connexions filaires existants, sans attendre les ports independants ni la chaine FTTH. Les ports et le brassage precederont ensuite la chaine FTTH.

**Avancement :** les socles des lots 1 (antennes et partage radio) et 2 (faisceaux hertziens) sont implementes et valides automatiquement. Le prochain lot principal est le **lot 3 : optimisation des reseaux monumentaux**, prioritaire avant les pylones mobiles 3D et les ports independants. Les ports et le brassage restent places avant la chaine FTTH. La recette interactive, les benchmarks et les complements explicites ci-dessous restent ouverts.

Les autres fonctionnalites ouvertes ci-dessous (Wi-Fi, cuivre, services internes, complements des speedtests et outils) restent au programme, sans passer devant ces cinq lots. Les sections suivantes sont organisees par theme ; le tableau ci-dessus definit leur priorite de realisation.

La securite, les tests et l'optimisation accompagnent chaque priorite ; ils ne sont pas repousses a la fin.

## 4. Couverture calculee sur la carte

### Fond de carte

- [x] Selectionner une zone dans le dashboard et lancer explicitement sa preparation terrain ou son calcul radio, avec progression, annulation et limites de taille.

- [x] Conserver chaque tuile de terrain sur disque dans le monde apres sa premiere creation, sans expiration ni recalcul, meme apres redemarrage.
- [x] Garder les caches RAM limites et separer les images des differents mondes. Le fond reste fige ; seule la couche de couverture radio suit les modifications des antennes et des obstacles.
- [x] Construire progressivement une seule image globale a partir des PNG captures, sans recalculer le terrain. Un telechargement du fond par revision, aucun au zoom ou au deplacement ; resolution bornee pour eviter une image demesuree.

**Objectif : une couche coloree issue du meme moteur radio que le smartphone, pas un simple cercle autour de l'antenne et pas uniquement les endroits parcourus par le joueur.**

Minecraft fournit le terrain et les blocs ; le moteur radio du mod calcule leur effet sur le signal. Le resultat reste une simulation adaptee au jeu, pas un calcul electromagnetique exact.

### Affichage et comportement

- [x] Ajouter un calque "Couverture calculee" sur la carte web.
- [x] Ajouter ce calque a la carte en jeu avec les memes donnees.
- [x] Calculer la reception avec `SignalPropagator` partage entre smartphone et carte : distance, frequence, relief, blocs traverses, categories de materiaux et epaisseur des obstacles.
- [x] Afficher les zones en signal fort, moyen, faible ou absent, avec legende ; proposer une antenne selectionnee ou l'ensemble du reseau, et des filtres par technologie/bande.
- [x] Conserver des resultats independants 2G/3G/4G/5G et proposer les pas 1/8/16 blocs. Partager les parcours d'obstacles entre bandes et reutiliser les resultats au changement de technologie.
- [x] Calculer par defaut a hauteur de reception au-dessus du sol ; permettre une hauteur Y choisie pour examiner un etage, un tunnel ou une zone interieure. Indiquer cette hauteur et la precision de la grille.
- [x] Au survol, afficher le signal estime, la technologie et l'antenne retenue. Distinguer reception radio et presence d'un chemin vers un serveur si l'antenne n'est plus raccordee.
- [x] Invalider les calculs concernes apres pose/retrait d'une antenne, modification de ses bandes ou construction/destruction d'obstacles, y compris sans notification des voisins.
- [x] Integrer orientation et puissance dans la couverture quand ces reglages seront disponibles.
- [ ] Conserver le calque des releves smartphone separement, avec leur date, pour comparer mesures et couverture calculee.

### Calcul sans ralentir le jeu

- [x] Calculer progressivement par tuiles, en priorite dans la zone visible ; limiter la precision selon le zoom et reutiliser les resultats en cache.
- [x] Invalider les tuiles affectees lorsqu'un obstacle change, meme s'il se situe entre l'antenne et la zone affichee ; rejeter les resultats issus d'une ancienne configuration.
- [x] Ne pas charger ou generer massivement des chunks pour la carte. Si le terrain du trajet manque, afficher "inconnu / donnees anciennes", jamais supposer de l'air ou annoncer une absence de signal certaine.
- [x] Conserver le terrain radio 3D observe dans un cache borne et persistant pour calculer aussi a travers les chunks decharges ; garder les donnees chargees prioritaires et les trajets jamais observes inconnus.
- [ ] Lire le monde uniquement sur le thread serveur ; effectuer les calculs lourds sur des donnees detachees, avec files et caches limites.

**Validation :** un mur ou une montagne modifie la couverture derriere l'obstacle ; retirer le mur actualise la zone ; le smartphone et la carte concordent au meme point, a la meme hauteur et avec les memes reglages et la meme antenne selectionnee. La carte predit la meilleure cellule sans historique ; le telephone applique aussi une hysteresis lors des deplacements. Dezoomer ne doit pas bloquer le serveur.

**Limites du lot actuel :** carte web de l'Overworld, pas souhaite 1/8/16 blocs avec apercu plus grossier au grand dezoom, portee maximale commune de 4 096 blocs, formes complexes simplifiees. Le pas effectif est affiche ; le mode bloc par bloc demande un zoom rapproche. Calcul fractionne (budget cooperatif de 2 ms/tick), 256 points maximum par tuile, parcours partages entre bandes et cache commun aux technologies. Les benchmarks a grande echelle restent a faire.

**Lot carte en jeu :** calque radio disponible dans la dimension du joueur, filtres technologie/bande/antenne, hauteur surface/mobile/Y libre, legende, progression et survol signal/service. Au plus 16 tuiles visibles, une requete tous les quatre ticks et expiration des donnees non revalidees ; les tuiles encore visibles sont conservees pendant les deplacements. Paquets bornes, anciennes vues rejetees, limites de calcul distinguees d'un serveur temporairement occupe. Tests de cache, de codecs, de controles serveur et de concordance avec smartphone/web ajoutes. La recette visuelle et le multijoueur interactif restent a realiser.

## 5. Mise en place du reseau

### Reseau fixe

- [x] Stabiliser les IP fixes apres recalcul et redemarrage, sans collisions.
- [ ] Ajouter ports, brassage et fibres independantes dans un cable ; un croisement de cables ne doit pas tout connecter automatiquement.
- [ ] Completer la chaine **NRO avec OLT -> PM -> PBO -> PTO -> ONT -> box**, avec des fonctions distinctes et une configuration simple.
- [ ] Gerer GPON/XGS-PON, partage de capacite et pertes optiques, avec une explication claire quand une liaison ne fonctionne pas.
- [ ] Ameliorer le cuivre : NRA/DSLAM, SR, ligne et modem ADSL/VDSL ; faire dependre le debit de la longueur et de la qualite de ligne.
- [ ] Ajouter poteaux, fourreaux, boitiers et outils de pose assistee pour construire facilement en aerien, en sous-sol et dans les batiments.
- [ ] Permettre des liaisons de collecte et de secours, par cable ou faisceau hertzien.

**Lot IP fixes :** les champs IP/CIDR existants servent de baux persistants par dimension. Les adresses valides uniques du pool 10/8 sont reservees avant toute allocation ; les anciennes adresses nulles, invalides ou dupliquees sont reparees de facon deterministe au chargement. Les nouvelles adresses utilisent un CIDR /32, distinct du pool mobile 172.16/12. Recalculer, couper ou fusionner des chemins ne renumerote plus les equipements. Tests codec/disque, migration, collisions, retrait de serveur et GameTests de coupure/reparation ajoutes ; la recette de migration d'un ancien monde complet reste a faire.

### Liaisons hertziennes entre sites

Un faisceau hertzien est une liaison radio directionnelle point a point, utilisee notamment pour raccorder un site mobile sans tirer de fibre jusqu'a lui. Il transporte le trafic entre les sites ; il ne remplace pas les antennes qui couvrent les telephones.

```text
Reseau filaire -> Site A [radio FH] ~~~ liaison sans fil ~~~ [radio FH] Site B -> Antenne mobile B
```

- [x] Ajouter une paire de radios/paraboles directionnelles, distinctes des antennes mobiles, a installer et orienter sur les sites A et B. Seuls les raccordements locaux aux equipements restent necessaires.
- [x] Permettre l'association des deux extremites et le choix d'un canal compatible ; calculer la qualite selon distance, alignement, frequence et obstacles du monde. Verifier aussi le degagement autour du trajet (zone de Fresnel simplifiee), pas seulement une ligne sans bloc.
- [x] Integrer la liaison au graphe avec capacite partagee, latence et etat reel : les utilisateurs de B restent limites par le debit du faisceau et par le reseau en amont de A.
- [x] Permettre des relais A -> B -> C pour contourner un obstacle, sans creer de connexion independante au reseau : chaque saut ajoute ses limites et dependances.
- [x] Afficher le faisceau sur la carte avec ses extremites, son debit et les obstacles bloquants ; reutiliser les donnees de terrain du moteur radio avec un profil adapte aux liaisons directionnelles.

**Validation :** B fournit un service sans cable entre A et B lorsque le faisceau et le chemin amont fonctionnent. Un obstacle ou un desalignement suffisant degrade ou coupe la liaison. Si le faisceau est coupe, B peut encore emettre un signal mobile, mais les services dependants de cette collecte deviennent indisponibles, sauf chemin de secours.

**Lot FH implemente :** bloc `microwave_dish`, recette, modele directionnel et configuration persistante. Association reciproque, 16 canaux, profils 6/11/18/38 GHz (300/600/1000/2000 Mbps nominaux), azimut et elevation avec aide a la visee. Budget radio dependant de la distance et de l'alignement aux deux extremites ; controle conservateur du volume a 60 % de la premiere zone de Fresnel, sans trous entre quelques sondages sur des anneaux. Les obstacles et donnees inconnues interdisent la mise en service du lien.

**Integration :** capacite FH partagee DOWN + UP, latence par saut, contraintes filaires et de collecte conservees. Relais avec deux paraboles interconnectees au site intermediaire. Les recalculs de cables conservent les FH valides ; leurs edges derives ne sont pas sauvegardes comme une preuve de terrain libre. Les trajets longs reprennent au point inconnu au lieu de recommencer a chaque chargement de cache. Un dechargement de chunk correctement capture ne coupe pas une liaison valide ; les mutations reelles invalident les liens concernes. Diagnostics coherents et liens coupes visibles sur les cartes web et en jeu.

**Bornes et securite :** 4096 blocs de portee, 8192 noeuds, 256 paraboles et 128 liaisons/diagnostics par dimension ; budget de propagation de 2 ms par tick et 1048576 operations candidates par trajet, sans chargement force de chunks. Protocole **1.6**. Configurations controlees cote serveur, vues liees a la dimension et a l'entite ouverte, paquets bornes et rafraichissements correles. Les mises a jour ne remplacent pas les brouillons ni une parabole remplacee au meme emplacement.

**Validation du lot :** 704 tests Java, 163 tests JavaScript et 11 GameTests reussis ; build du mod et ressources web reconstruits. Les tests couvrent le transport reel, les supports solides, le mur pose en mode strict, les relais, les coupures de collecte, les voxels interieurs de Fresnel, le dechargement et une liaison de 4096 blocs sur 257 chunks relus depuis le disque avec un cache RAM de 256 entrees. La recette visuelle et multijoueur interactive, la migration d'un monde complet et les benchmarks restent a effectuer. La meteo, les interferences entre FH et la gestion dynamique de canaux ne sont pas simulees.

### Mobile et reseau local

- [x] Ajouter secteurs d'antenne, orientation, inclinaison, puissance et largeur de bande ; ces reglages doivent agir sur la reception et sur la carte.
- [x] Partager correctement la capacite radio entre les telephones et tenir compte des interferences et de la liaison de collecte.
- [x] Ameliorer le choix de l'antenne et le passage entre cellules en mouvement, sans basculements permanents.
- [ ] Distinguer 4G, 5G NSA et SA de facon accessible.
- [ ] Permettre des reseaux mobiles nommes et une association simple du telephone a son reseau, sans abonnement payant ni gestion commerciale.
- [ ] Ajouter switches, ports Ethernet et points d'acces Wi-Fi avec SSID, mot de passe, canaux et attenuation par les murs.
- [ ] Fournir une configuration IP automatique fonctionnelle, puis des reglages manuels optionnels : DHCP, DNS, IPv6, VLAN et routage simple.

**Lot antennes et partage radio :** reglages persistants (omnidirectionnel ou 1/2/3 secteurs, azimut, inclinaison, puissance et largeur relative), valeurs par defaut compatibles avec les anciennes antennes et propagation commune au smartphone et aux cartes. Allocation par bande avec capacites additives, budget DOWN/UP normalise et contraintes de collecte conservees ; les credits d'arrondi reserves avant allocation evitent la famine du scenario de concurrence ponderee teste. Interferences simplifiees selon les spectres et puissances recus. Scan commun au trafic passif, au telephone et aux tests actifs ; hysteresis de 3 dB / 40 ticks, reselection sur perte de cellule, adaptation des debits et du ping sans changer la session ni le serveur choisi.

**Bornes et limites :** cache de 256 telephones par dimension, scan borne a 8192 noeuds, 128 antennes et 65536 sondages ; depassements explicites, aucune generation forcee de chunks. Repartition proportionnelle fixe entre porteuses, capacite partagee entre secteurs d'un site, emetteurs supposes actifs et granularite d'un Mbps. Pas de modelisation NSA/SA ni de configuration independante par secteur. Les cartes restent des predictions de puissance sans historique d'attachement, pas des cartes de debit garanti. Protocole Minecraft **1.5**, configurations liees a la dimension et actualisations d'antenne correlees a la vue ; le suivi ne remplace pas les modifications en attente.

**Validation du lot :** 596 tests Java, 158 tests JavaScript et 9 GameTests reussis, build du mod et ressources web reconstruits. Tests de persistance, de codecs, de controles serveur, de rotation en monde reel, de partage multibande et d'equite sous 256 flux ajoutes. La recette visuelle, le multijoueur interactif, la migration d'un monde complet et les benchmarks de charge restent a realiser.

### Pylones mobiles modulaires en 3D

Construire un pylone fonctionnel avec deux blocs distincts : un emetteur mobile en haut et des elements de support empilables qui transportent aussi la fibre. Ce lot concerne les antennes relais mobiles, pas les paraboles FH.

- [ ] Donner au bloc emetteur un modele 3D realiste : panneaux verticaux autour d'un chassis central, brides, boitiers et petits cables visibles. Faire correspondre son aspect aux secteurs et a l'orientation configures, tout en conservant les fonctions et reglages des antennes existantes.
- [ ] Ajouter un bloc pilier/support metallique empilable, plus fin qu'un cube plein, avec raccords visuels continus et gaine ou chemin de cables integre. Le pied utilise ce meme bloc de support, sans imposer un troisieme type de bloc.
- [ ] Faire fonctionner chaque support comme un segment de fibre standard dans le graphe : raccordement de la fibre au pied, continuite entre les supports et connexion a l'emetteur au sommet, sans colonne de cables supplementaire. Appliquer les memes limites et regles de partage que la fibre standard, sans emission radio par les supports.
- [ ] Utiliser la position et la hauteur reelles du bloc emetteur pour la couverture du smartphone et des cartes. La rupture d'un support doit couper la collecte qui le traverse ; un signal mobile peut rester present, mais les services deviennent indisponibles sans autre chemin amont.
- [ ] Ajouter recettes, butin et traductions ; verifier les modeles en jeu, les raccordements, les debits, la conservation des reglages et des IP, ainsi que la sauvegarde et le rechargement du pylone.

**Validation :** construire un pylone de plusieurs supports, raccorder uniquement son pied au reseau et obtenir du service depuis l'emetteur au sommet. Modifier sa hauteur doit agir sur la couverture. Casser puis replacer un support doit interrompre puis retablir la collecte, sans creer de liaison fictive ni renumeroter les equipements restes en place.

## 6. Utilisation du reseau

- [x] Unifier les debits montants/descendants du modele filaire existant : cables, equipements, collecte des antennes, outils et dashboard utilisent les capacites appliquees par le moteur et les memes compteurs.
- [x] Etendre cette coherence au partage radio par bande, avec le lot de reseau mobile.
- [ ] Etendre cette coherence aux futurs ports independants, avec le lot de construction.
- [x] Refaire l'interface du speedtest sur telephone, routeur et dashboard : compteur anime, courbes de mesures, progression des phases et bilan avec debits moyens calcules par le moteur.
- [ ] Completer les statistiques du speedtest : pics globaux, echantillonnage du ping, gigue et pertes mesures par le moteur, au-dela du ping actuel et du maximum observe par chaque interface.
- [x] Permettre de choisir le serveur de speedtest depuis le telephone, le routeur et le dashboard : liste des serveurs avec nom, identifiant, latence estimee et disponibilite, plus un mode automatique.
- [x] Tester reellement vers le serveur choisi : verifier le chemin cote serveur, afficher la destination utilisee et signaler une indisponibilite sans basculer silencieusement sur un autre serveur.
- [ ] Identifier et expliquer visuellement le maillon limitant du trajet, au-dela de sa capacite estimee et des erreurs de connexion.
- [x] Clarifier la duree totale du test et conserver une courbe recente en memoire par appareil, sans perturber les tests simultanes des autres appareils.
- [ ] Permettre l'annulation d'un test et conserver un historique de plusieurs resultats par appareil, distinct de la courbe glissante du test observe.
- [x] Autoriser plusieurs speedtests simultanes sur des appareils distincts, avec un test par appareil, un suivi independant et le partage des liens communs.
- [ ] Implementer SMS, contacts et numeros entre joueurs, puis appels si une integration vocale adaptee est disponible.
- [ ] Ajouter des services internes au monde : petites pages hebergees, messagerie et transferts de fichiers virtuels, sans acces arbitraire au vrai Internet.
- [ ] Ajouter capteurs, affichages et commandes redstone a distance dont le fonctionnement depend du reseau.
- [ ] Garder un outil simple pour voir le trajet d'une connexion et comprendre une coupure, un mauvais brassage ou une saturation, sans systeme de tickets.
- [ ] Ameliorer la carte : recherche d'equipements, noms, liens physiques, debits et filtres lisibles.

**Lot choix du serveur :** catalogue borne a 128 entrees, parcours unique et limites de 8192 noeuds / 16384 liens. La selection reste explicite en cas d'indisponibilite, la destination est reprise dans le suivi et les resultats, et les erreurs de perte de trajet sont traduites. Protocole Minecraft 1.3 avec validation de la dimension au demarrage ; API web avec serverId optionnel. Tests moteur, codecs, handlers, interfaces et HTTP ajoutes. Les noms du catalogue sont generes a partir des coordonnees ; le renommage et la recette visuelle multijoueur restent a realiser.

**Lot coherence des debits filaires :** capacites nominales/effectives centralisees, attenuation cuivre par segment, budgets DOWN/UP des equipements et budget DOWN+UP partage des liens. Allocation max-min avec redistribution, credits d'arrondi bornes et absence de famine dans les scenarios testes. Deduplication des positions physiques et separation des extremites ; outils, ecran serveur et dashboard affichent les valeurs appliquees. Cache physique par revision, plafonds de 262144 references pour la geometrie et pour les ressources des requetes d'allocation. Les refus de quota sont explicites et un test excedentaire ne supprime pas ceux deja admis.

**Migration et validation du lot :** `CapacityModelVersion=1`, conservation des IP et des plafonds personnalises du nouveau modele, synchronisation differee des anciens routeurs avec leur bloc. Protocole Minecraft 1.4 ; fermeture et changement de vue proteges contre les refresh tardifs. Validation automatisee : 452 tests Java, 139 tests JavaScript et 8 GameTests reussis. La recette visuelle et les benchmarks de charge restent ouverts. La duree totale, l'annulation, l'historique et les statistiques avancees des speedtests ne sont pas inclus dans ce lot.

**Lot presentation et fluctuations des speedtests :** montee en charge d'une seconde puis variations lentes de demande entre 94 et 100 % du plafond, avant allocation et uniquement pour les tests manuels. Courbes des mesures recues, moyennes des allocations par phase incluant les zeros, maintien des budgets et du partage existant. Les pauses de recalcul ne deviennent pas des mesures nulles ; les resultats manuels ne sont plus remplaces par le trafic passif. Courbes glissantes bornees a 120 points par phase et 256 appareils, duree totale explicite, gel sur donnees anciennes et progression d'echec inconnue si aucune phase n'a ete observee. Le protocole reste en 1.4. Validation : 497 tests Java, 157 tests JavaScript et 8 GameTests reussis ; recette visuelle en jeu et navigateur non effectuee.

## 7. Qualite et optimisation a maintenir

### Lot prioritaire : optimisation des reseaux monumentaux

**Besoin :** le reseau utilisateur compte deja environ 200 routeurs et 20 antennes et doit pouvoir grandir fortement. Une pose ou une casse locale ne doit plus reconstruire systematiquement tout le reseau.

**Constat dans le code :** `NetworkTracer.recalculateNetwork` repart de chaque equipement pour chaque type de cable, demande des chunks complets avec chargement/generation possible et copie les chemins a chaque progression. Le recalcul est execute en une seule fois sur le thread serveur, avec une pause de l'allocation du trafic pour ce tick. Les modifications d'un meme tick sont deja regroupees, mais pas celles d'une construction continue sur plusieurs ticks. Le poids de chaque cout reste a mesurer.

- [x] Instrumenter les declenchements et mesurer une reference avant optimisation : temps de tick (dont p95/p99), duree des recalculs, blocs parcourus, allocations memoire, chunks demandes et delai avant disponibilite d'une nouvelle liaison. Inclure les recalculs declenches a la connexion d'un joueur.
- [ ] Enregistrer la position et la nature de chaque pose, casse ou remplacement, plutot qu'un simple indicateur global de recalcul ; couvrir aussi les modifications sans notification des voisins.
- [ ] Maintenir un index du cablage par chunk et des dependances entre blocs physiques, troncons, jonctions et equipements, afin de retrouver directement les liaisons touchees.
- [ ] Compresser les chaines sans embranchement en troncons entre points utiles. Conserver longueur, type, capacites, attenuation et dependances physiques pour preserver les debits partages ; eviter les copies completes de chemins a chaque pas de parcours.
- [ ] Mettre a jour uniquement les troncons et composantes affectes par une extension, une fusion ou une coupure. Ne pas reconstruire les quartiers independants ; invalider les caches de chemins concernes, y compris lorsqu'une nouvelle liaison cree un meilleur trajet.
- [ ] Regrouper les poses rapprochees dans une file dedupliquee, avec un delai maximal de traitement pour ne pas repousser indefiniment les mises a jour pendant une construction continue.
- [ ] Decouper les travaux en operations reprenables, avec limites de file, budget de temps et quota d'operations par tick. Viser initialement 1 a 2 ms par tick pour cette tache, a ajuster apres mesures, sans promettre une garantie de temps reel strict.
- [ ] Retirer immediatement les connexions cassees, maintenir le trafic et les resultats non concernes, puis publier atomiquement les nouveaux raccordements valides. Rejeter les resultats calcules sur une revision devenue obsolete et conserver les IP et les FH non affectes.
- [ ] Persister le cablage connu et le verifier localement au chargement des chunks, sans les charger ou les generer pour retracer le reseau. Garder les lectures du monde sur le thread serveur ; reserver un eventuel calcul en arriere-plan a des instantanes immuables.
- [ ] Reserver la reconstruction complete a la migration, a la recuperation ou a une commande explicite, elle aussi progressive ; ne pas relancer tout le reseau a chaque connexion de joueur sans changement de topologie.
- [ ] Ajouter des benchmarks reproductibles a l'echelle actuelle (200 routeurs, 20 antennes), puis a 1000 et 5000 routeurs : longues lignes, embranchements, boucles, poses en rafale, coupures de dorsale, chunks decharges, cartes ouvertes et trafic simultane. Reevaluer les plafonds existants apres mesures, pas par simple augmentation des limites.

**Etape instrumentation implementee :** `/telecom diagnostics start|status|stop`, admin et par dimension, collecte desactivee par defaut, 1200 ticks / 128 recalculs maximum en memoire. Causes fusionnees sans confondre le timer de connexion avec les demandes immediates, comptage des lectures/demandes de chunks/copies de chemins, allocations du thread si disponibles et delai jusqu'a publication. Les commandes de mesure ne changent pas les demandes en attente, les IP, les FH ni la pause historique du trafic. La prochaine etape est l'enregistrement des positions et de la nature des mutations, avant l'index local du cablage.

**Premiere reference mesuree :** `./gradlew runGameTestServer -PnetworkBaseline=true`, fixture de 200 routeurs / 20 antennes / 20 serveurs / 860 cables, 8 passages de chauffe, 40 coupures/reparations locales et un timer de connexion. Dernier passage du 18 septembre 2026 sur macOS ARM64 / Java 21.0.11 : 222 ticks, p95/p99 12,679/14,511 ms ; 41 recalculs, p95/p99 14,160/59,667 ms. Une coupure locale lit encore 57090 blocs, demande 57330 fois un chunk et alloue environ 6,3 a 6,4 Mo. Les temps varient entre passages, sans garantie de fluidite. Le timer de connexion attend 101 ticks ; GameTest accelere le temps et ce n'est pas une mesure de login multijoueur reel. Details, limites et reproduction dans le README, echantillons dans les logs `NETWORK_BASELINE_SAMPLE`.

**Validation et perimetre :** 18 nouveaux tests Java de diagnostics/ordonnancement, build complet de 722 tests Java et 12 GameTests avec benchmark reussis. Deux tests HTTP ont echoue au premier passage, puis ont reussi seuls et dans le second build complet, sans modification HTTP. Cette etape mesure mais n'optimise pas encore le traceur ; les lectures du monde restent sur le thread serveur. La fixture ne couvre ni trafic/cartes simultanes, ni chunks decharges, ni 1000/5000 routeurs ; le benchmark etendu et les mesures du monde utilisateur restent ouverts.

**Validation :** une modification locale sur une branche ne parcourt pas les quartiers independants et ne suspend pas leur trafic. Les coupures, fusions, debits partages et chemins obtenus restent coherents avec une reconstruction de reference. Une modification de dorsale peut toucher de nombreuses routes : son cout doit etre reparti entre plusieurs ticks, avec progression visible et files bornees. Publier les mesures avant/apres et les limites observees, sans garantir une capacite monumentale sur la seule base du nombre d'equipements.

### Suivi transversal

- [ ] Remplacer les recalculs globaux par des mises a jour locales ; compresser les longs chemins de cables et rechercher seulement les antennes proches.
- [ ] Conserver le reseau logique lorsque les chunks se dechargent, sans maintenir tout le monde charge ; distinguer cet etat des donnees de terrain indisponibles pour la couverture.
- [ ] Limiter memoire, sessions, historique, caches et donnees envoyees aux cartes ; afficher les calculs en attente plutot que figer le jeu.
- [ ] Mesurer le cout du mod sur petit reseau, ville et forte charge, en particulier avec plusieurs cartes de couverture ouvertes.
- [ ] Verifier visuellement le client, le multijoueur, les sauvegardes et le redemarrage complet ; ajouter un test pour chaque correction importante.
- [ ] Conserver les controles d'acces et validations serveur, avec une protection simple contre la modification du reseau d'un autre joueur.
- [ ] Completer traductions, recettes et butin ; fournir des interfaces et un guide faciles a utiliser, sans imposer les reglages experts.

**Une fonctionnalite est terminee lorsqu'elle fonctionne en jeu, utilise les memes regles que les outils de mesure, conserve ses donnees et reste fluide dans les scenarios testes.** Les cases cochees decrivent les implementations validees automatiquement ; les limites de recette visuelle, de migration de mondes complets et de charge restent explicitement suivies ci-dessus.
