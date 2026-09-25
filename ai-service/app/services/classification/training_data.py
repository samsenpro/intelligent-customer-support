"""Corpus etiquetado (español e inglés) con el que se entrena el clasificador Naive Bayes al arrancar.

Es pequeño a propósito: el modelo complementa a las reglas en frases que no siguen un patrón fijo.
Para producción se sustituiría por un corpus real de conversaciones etiquetadas.
"""

from app.services.classification.taxonomy import Intent

TRAINING_EXAMPLES: dict[Intent, list[str]] = {
    Intent.GREETING: [
        "hola", "buenos días", "buenas tardes, ¿cómo están?", "hola, buenas noches", "hello", "hi there",
        "good morning", "hey, how are you",
    ],
    Intent.GENERAL_QUESTION: [
        "¿cuál es el horario de atención?", "¿tienen tiendas físicas?", "¿en qué ciudades están?",
        "quisiera información general", "¿cómo funciona el servicio?", "what are your opening hours",
        "do you have physical stores", "how does your service work", "¿tienen número de teléfono?",
    ],
    Intent.PRODUCT_INFO: [
        "¿el producto tiene garantía?", "¿de qué material está hecho?", "¿qué tallas tienen disponibles?",
        "¿es compatible con mi celular?", "quiero saber las características del modelo", "¿hay stock del color negro?",
        "does this product have warranty", "is it compatible with iphone", "what sizes are available",
        "tell me the specifications of the product",
    ],
    Intent.ORDER_STATUS: [
        "¿dónde está mi pedido?", "¿cuándo llega mi compra?", "quiero rastrear mi orden",
        "no sé en qué estado está mi pedido", "¿ya despacharon mi paquete?", "necesito el número de guía",
        "where is my order", "when will my package arrive", "track my order", "has my order shipped",
    ],
    Intent.SHIPPING_ISSUE: [
        "mi pedido llegó roto", "el paquete llegó incompleto", "me enviaron un producto equivocado",
        "el envío está muy retrasado", "nunca llegó mi paquete", "la caja llegó dañada",
        "my package arrived damaged", "i received the wrong item", "the delivery is late", "my parcel never arrived",
    ],
    Intent.REFUND_REQUEST: [
        "quiero que me devuelvan el dinero", "quiero un reembolso", "¿cómo solicito la devolución?",
        "quiero devolver el producto", "necesito el reintegro de mi pago", "¿cuánto tarda el reembolso?",
        "i want a refund", "how do i return this item", "i want my money back", "refund my order please",
    ],
    Intent.BILLING_QUESTION: [
        "necesito la factura de mi compra", "¿qué medios de pago aceptan?", "¿el precio incluye iva?",
        "¿puedo pagar en cuotas?", "no me llegó la factura electrónica", "¿cuánto cuesta el envío?",
        "i need the invoice", "which payment methods do you accept", "does the price include taxes",
        "can i pay in installments",
    ],
    Intent.PAYMENT_ISSUE: [
        "me cobraron dos veces", "mi tarjeta fue rechazada", "el pago falló pero me descontaron",
        "no me deja pagar", "me hicieron un cobro doble", "el cargo aparece duplicado",
        "i was charged twice", "my card was declined", "payment failed but money was taken",
        "there is a double charge on my card",
    ],
    Intent.CANCELLATION: [
        "quiero cancelar mi pedido", "cancelen mi suscripción", "quiero dar de baja mi cuenta",
        "necesito anular la compra", "¿cómo cancelo el plan?", "i want to cancel my order",
        "cancel my subscription", "how do i cancel my plan", "please cancel my purchase",
    ],
    Intent.TECHNICAL_ISSUE: [
        "la aplicación no funciona", "la página me da error", "la app se cierra sola",
        "no carga el carrito de compras", "sale un error al pagar en la web", "el sitio está caído",
        "the app is not working", "the website shows an error", "the app keeps crashing", "the page won't load",
    ],
    Intent.ACCOUNT_ACCESS: [
        "no puedo entrar a mi cuenta", "olvidé mi contraseña", "mi cuenta está bloqueada",
        "no me llega el código de verificación", "quiero cambiar mi correo de la cuenta", "no puedo iniciar sesión",
        "i can't log in", "i forgot my password", "my account is locked", "i did not receive the verification code",
    ],
    Intent.FRAUD_REPORT: [
        "hay compras que yo no hice", "me hackearon la cuenta", "aparece un cargo que no reconozco",
        "creo que es un fraude", "alguien usó mi tarjeta", "me robaron la cuenta",
        "there are purchases i did not make", "my account was hacked", "unauthorized charge on my card",
        "someone used my card",
    ],
    Intent.COMPLAINT: [
        "el servicio es pésimo", "estoy muy molesto con la atención", "esto es inaceptable",
        "quiero poner una queja", "nunca me resuelven nada", "terrible experiencia de compra",
        "this is unacceptable", "worst service ever", "i want to file a complaint", "i am very disappointed",
    ],
    Intent.LEGAL_REQUEST: [
        "voy a poner una denuncia", "quiero que borren mis datos personales", "hablaré con mi abogado",
        "pondré la queja ante la superintendencia", "solicito mis datos según habeas data",
        "i will contact my lawyer", "delete my personal data", "i will take legal action",
    ],
    Intent.HUMAN_REQUEST: [
        "quiero hablar con un agente", "comuníqueme con una persona", "necesito un asesor humano",
        "pásame con alguien real", "no quiero hablar con un robot", "talk to a human",
        "i want to speak with an agent", "connect me with a real person", "i need a representative",
    ],
}
