package com.supportmind.demo;

import com.supportmind.agent.AgentService;
import com.supportmind.auth.AuthenticatedUser;
import com.supportmind.auth.Role;
import com.supportmind.auth.User;
import com.supportmind.auth.UserRepository;
import com.supportmind.customer.Customer;
import com.supportmind.customer.CustomerRepository;
import com.supportmind.knowledge.KnowledgeDtos.KnowledgeRequest;
import com.supportmind.knowledge.KnowledgeService;
import com.supportmind.knowledge.KnowledgeStatus;
import com.supportmind.knowledge.KnowledgeType;
import com.supportmind.organization.Organization;
import com.supportmind.organization.NoContextAction;
import com.supportmind.organization.OrganizationDtos.UpdateAiSettingsRequest;
import com.supportmind.organization.OrganizationRepository;
import com.supportmind.organization.OrganizationService;
import com.supportmind.ticket.TicketCategory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

/**
 * Datos de demostración (DEMO_DATA_ENABLED=true): la organización "Acme Store" con un usuario de
 * cada rol, algunos clientes y una base de conocimiento publicada (que se indexa en el vector store).
 * Es idempotente: si la organización ya existe no hace nada.
 */
@Component
@ConditionalOnProperty(prefix = "supportmind.demo", name = "enabled", havingValue = "true")
public class DemoDataSeeder implements ApplicationRunner {

    public static final String SLUG = "acme-store";
    public static final String DOMAIN = "@acme-store.example";

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    private final OrganizationRepository organizationRepository;
    private final OrganizationService organizationService;
    private final UserRepository userRepository;
    private final CustomerRepository customerRepository;
    private final AgentService agentService;
    private final KnowledgeService knowledgeService;
    private final PasswordEncoder passwordEncoder;
    private final TransactionTemplate transaction;
    private final String password;

    public DemoDataSeeder(OrganizationRepository organizationRepository, OrganizationService organizationService,
                          UserRepository userRepository, CustomerRepository customerRepository,
                          AgentService agentService, KnowledgeService knowledgeService,
                          PasswordEncoder passwordEncoder, PlatformTransactionManager transactionManager,
                          @Value("${supportmind.demo.password:}") String password) {
        this.organizationRepository = organizationRepository;
        this.organizationService = organizationService;
        this.userRepository = userRepository;
        this.customerRepository = customerRepository;
        this.agentService = agentService;
        this.knowledgeService = knowledgeService;
        this.passwordEncoder = passwordEncoder;
        this.transaction = new TransactionTemplate(transactionManager);
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (password == null || password.length() < 12 || !password.matches("^(?=.*[A-Za-z])(?=.*\\d).*$")) {
            throw new IllegalStateException(
                    "DEMO_USER_PASSWORD must have at least 12 characters with letters and digits");
        }
        if (organizationRepository.existsBySlug(SLUG)) {
            log.info("Demo data already present");
            return;
        }
        transaction.executeWithoutResult(status -> seed());
        log.info("Demo organization '{}' created: users admin{}, supervisor{}, agent{}, agent2{}, customer{}",
                SLUG, DOMAIN, DOMAIN, DOMAIN, DOMAIN, DOMAIN);
    }

    private void seed() {
        Organization organization = organizationService.create("Acme Store");
        String hash = passwordEncoder.encode(password);
        User admin = staff(organization, "admin", "Laura Admin", Role.ADMIN, hash);
        staff(organization, "supervisor", "Sofía Supervisora", Role.SUPERVISOR, hash);
        staff(organization, "agent", "Carlos Agente", Role.AGENT, hash);
        staff(organization, "agent2", "Diana Agente", Role.AGENT, hash);

        User customerUser = userRepository.save(new User(organization, "customer" + DOMAIN, hash, "Ana Martínez",
                Role.CUSTOMER));
        Customer ana = new Customer(organization.getId(), "Ana Martínez", "customer" + DOMAIN, "+57 300 123 4567",
                "CRM-1001");
        ana.linkUser(customerUser.getId());
        customerRepository.save(ana);
        customerRepository.save(new Customer(organization.getId(), "Pedro Ramírez", "pedro.ramirez@example.com",
                "+57 311 555 0101", "CRM-1002"));
        customerRepository.save(new Customer(organization.getId(), "María López", "maria.lopez@example.com", null,
                "CRM-1003"));

        AuthenticatedUser adminPrincipal = AuthenticatedUser.from(admin);
        organizationService.updateAiSettings(adminPrincipal, new UpdateAiSettingsRequest(true, new BigDecimal("0.55"),
                NoContextAction.ASK_MORE_INFO, 2,
                Set.of(TicketCategory.SECURITY, TicketCategory.LEGAL), true, true, true, 12));
        for (KnowledgeRequest document : DemoKnowledgeBase.documents()) {
            knowledgeService.create(adminPrincipal, document);
        }
    }

    private User staff(Organization organization, String alias, String name, Role role, String hash) {
        User user = userRepository.save(new User(organization, alias + DOMAIN, hash, name, role));
        agentService.createProfile(user);
        return user;
    }

    /** Base de conocimiento de ejemplo de una tienda online de electrónica. */
    static final class DemoKnowledgeBase {

        private DemoKnowledgeBase() {
        }

        static List<KnowledgeRequest> documents() {
            return List.of(
                    published("Política de reembolsos", KnowledgeType.POLICY, """
                            # Política de reembolsos

                            Puedes solicitar el reembolso de un producto dentro de los 30 días calendario siguientes \
                            a la entrega. El producto debe estar sin usar, con todos sus accesorios y en su empaque original.

                            ## Cómo solicitarlo

                            Escríbenos por el chat indicando el número de pedido y el motivo. Te enviaremos una guía \
                            de devolución prepagada por correo electrónico.

                            ## Plazos

                            Cuando recibimos y revisamos el producto, aprobamos el reembolso en un máximo de 3 días \
                            hábiles. El dinero se acredita en el mismo medio de pago en un plazo de 5 a 10 días hábiles.

                            ## Excepciones

                            No se aceptan devoluciones de productos personalizados ni de tarjetas de regalo. Si el \
                            producto llegó defectuoso, el envío de la devolución no tiene costo para el cliente."""),
                    published("Política de envíos", KnowledgeType.POLICY, """
                            # Política de envíos

                            Despachamos los pedidos en un máximo de 24 horas hábiles después de confirmado el pago.

                            El envío estándar tarda de 3 a 5 días hábiles en ciudades principales y de 5 a 8 días \
                            hábiles en el resto del país. El envío express tarda 1 día hábil y solo está disponible \
                            en Bogotá, Medellín y Cali.

                            El envío estándar es gratis en compras superiores a $150.000. En compras menores cuesta \
                            $12.900. El envío express cuesta $24.900.

                            Cuando despachamos tu pedido te enviamos por correo el número de guía para rastrearlo en \
                            la página de la transportadora."""),
                    published("Garantía de productos", KnowledgeType.POLICY, """
                            # Garantía

                            Todos los productos electrónicos tienen 12 meses de garantía por defectos de fábrica, \
                            contados desde la fecha de entrega. Los accesorios (cables, fundas, cargadores) tienen \
                            6 meses de garantía.

                            La garantía no cubre daños por golpes, humedad o manipulación por terceros.

                            Para hacer válida la garantía escríbenos con el número de pedido, una descripción del \
                            problema y fotos o un video del defecto. El diagnóstico tarda hasta 15 días hábiles; si \
                            el defecto se confirma, reparamos o cambiamos el producto."""),
                    published("Métodos de pago y facturación", KnowledgeType.FAQ, """
                            # Métodos de pago

                            Aceptamos tarjetas de crédito y débito Visa, Mastercard y American Express, PSE, Nequi \
                            y pago contra entrega (solo en pedidos de hasta $500.000).

                            Con tarjeta de crédito puedes pagar hasta en 12 cuotas; el número de cuotas lo eliges \
                            con tu banco.

                            # Facturación

                            Enviamos la factura electrónica al correo registrado en un máximo de 48 horas después \
                            del pago. Si necesitas la factura a nombre de una empresa, indica el NIT y la razón \
                            social antes de pagar: una factura emitida no se puede modificar.

                            Si ves un cobro duplicado, no intentes pagar de nuevo: escríbenos y lo revisamos."""),
                    published("Manual de los auriculares SoundMax Pro", KnowledgeType.MANUAL, """
                            # SoundMax Pro: manual rápido

                            ## Emparejamiento Bluetooth

                            Con el estuche abierto, mantén pulsado el botón del estuche durante 3 segundos hasta que \
                            la luz parpadee en blanco. Busca "SoundMax Pro" en el Bluetooth de tu teléfono.

                            ## Batería

                            Los auriculares ofrecen hasta 8 horas de reproducción y el estuche aporta 24 horas \
                            adicionales. Una carga de 10 minutos da 2 horas de uso. La carga completa tarda 90 minutos.

                            ## Restablecer de fábrica

                            Si un auricular no se conecta, colócalos en el estuche y mantén pulsado el botón durante \
                            15 segundos hasta que la luz parpadee en rojo tres veces. Después vuelve a emparejarlos.

                            ## Resistencia al agua

                            Tienen certificación IPX4: resisten el sudor y las salpicaduras, pero no se pueden sumergir."""),
                    published("Recuperación de acceso a la cuenta", KnowledgeType.PROCEDURE, """
                            # Recuperar el acceso a tu cuenta

                            1. En la página de inicio de sesión pulsa "Olvidé mi contraseña".
                            2. Escribe el correo con el que te registraste. Te enviaremos un enlace válido por 30 minutos.
                            3. Abre el enlace y crea una contraseña nueva de al menos 10 caracteres.

                            Si no recibes el correo en 10 minutos, revisa la carpeta de spam. Por seguridad, la cuenta \
                            se bloquea durante 15 minutos después de 5 intentos fallidos de inicio de sesión.

                            Nuestro equipo nunca te pedirá tu contraseña por chat, correo o teléfono."""),
                    published("Preguntas frecuentes", KnowledgeType.FAQ, """
                            # Preguntas frecuentes

                            ¿Cuál es el horario de atención? El chat con agentes funciona de lunes a sábado de 8:00 a \
                            20:00. El asistente virtual responde las 24 horas.

                            ¿Tienen tiendas físicas? No, Acme Store es una tienda exclusivamente online.

                            ¿Puedo cambiar la dirección de entrega? Sí, mientras el pedido no haya sido despachado. \
                            Escríbenos por el chat con el número de pedido y la nueva dirección.

                            ¿Puedo cancelar un pedido? Sí, sin costo, mientras no haya sido despachado. Si ya fue \
                            despachado, debes esperar a recibirlo y solicitar la devolución."""));
        }

        private static KnowledgeRequest published(String title, KnowledgeType type, String content) {
            return new KnowledgeRequest(title, content, type, KnowledgeStatus.PUBLISHED);
        }
    }
}
