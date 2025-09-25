// MainViewModel.kt - Add this method to your existing MainViewModel class

// In your MainViewModel class, add this method:

fun resetPaymentState() {
    // Reset the payment state to prevent re-triggering
    // Assuming your paymentFragmentState is a MutableStateFlow or similar
    _paymentFragmentState.value = PaymentFragmentState.Loading // or whatever your initial/idle state is
}

// Alternative implementation if you're using a different state management pattern:
fun resetPaymentState() {
    // If using SharedFlow, you might not need to reset anything
    // as SharedFlow doesn't replay values by default
    
    // If using StateFlow with a specific idle state:
    _paymentFragmentState.value = PaymentFragmentState.Idle // if you have an Idle state
    
    // Or if you want to clear any cached state completely:
    _paymentFragmentState.tryEmit(PaymentFragmentState.Loading)
}